package io.annona.modules.voice.service;

import io.annona.common.model.ChatMessage;
import io.annona.common.model.ChatStreamListener;
import io.annona.common.model.StreamingChatProvider;
import io.annona.common.usage.UsageLedger;
import io.annona.common.voice.TtsOptions;
import io.annona.common.voice.TtsProvider;
import io.annona.modules.voice.config.VoiceProperties;
import io.annona.modules.voice.entity.VoiceSessionMessageEntity;
import io.annona.modules.voice.repository.VoiceSessionMessageRepository;
import io.annona.modules.voice.tts.OrderedTtsChunkEmitter;
import io.annona.modules.voice.tts.SentenceSplitter;
import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.question.QuestionCandidate;
import io.annona.shared.question.QuestionQueryService;
import io.annona.spi.dto.UsageInfo;
import io.annona.common.usage.UsageContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 面试官对话轮编排（P3-03，voice-adr 修订 1）：候选人提交一轮作答后，从题库队列取
 * 下一题，LLM 流式生成过渡语 + 题干（题干保持原意——P3-05 可比性的口径保证），增量
 * 经 {@link SentenceSplitter} 切句喂 {@link OrderedTtsChunkEmitter} 句级并发合成、按序
 * 下发（首句优先，边合成边播）；题目队列为空或越界时生成结束语。
 *
 * <p>事务与线程：本类方法全部无事务——LLM 流式与 TTS 都在 {@code aiIoExecutor} 上
 * （AGENTS §0.3 外部 IO 不进事务）；调用方（WS handler）只负责持久化与帧下发。
 * 计量：LLM 流式在 {@code UsageContext.bind(userId,"VOICE",...)} 内运行，token_usage
 * scene='VOICE'（V20）。
 */
@Service
public class VoiceInterviewService {

    private static final Logger log = LoggerFactory.getLogger(VoiceInterviewService.class);

    /** 面试官轮的下发回调（handler 实现：text 帧 + audio_chunk 帧 + 回声窗推进）。 */
    public interface TurnSink {
        void onText(String content);

        void onAudioChunk(int seq, byte[] wav, boolean isLast);
    }

    private final VoiceSessionService sessions;
    private final VoiceSessionMessageRepository messages;
    private final QuestionQueryService questions;
    private final DirectionQueryService directions;
    private final Optional<StreamingChatProvider> chat;
    private final Optional<TtsProvider> tts;
    private final UsageLedger usageLedger;
    private final VoiceProperties properties;
    private final Executor aiIo;

    public VoiceInterviewService(VoiceSessionService sessions,
                                 VoiceSessionMessageRepository messages,
                                 QuestionQueryService questions,
                                 DirectionQueryService directions,
                                 Optional<StreamingChatProvider> chat,
                                 Optional<TtsProvider> tts,
                                 UsageLedger usageLedger,
                                 VoiceProperties properties,
                                 @Qualifier("aiIoExecutor") Executor aiIo) {
        this.sessions = sessions;
        this.messages = messages;
        this.questions = questions;
        this.directions = directions;
        this.chat = chat;
        this.tts = tts;
        this.usageLedger = usageLedger;
        this.properties = properties;
        this.aiIo = aiIo;
    }

    /** 题库队列快照：开场（或重连重建）调用；无方向/题库空 → 空队列（自由问答，不进评分）。 */
    public List<QuestionCandidate> loadQueue(UUID userId, UUID directionId) {
        if (directionId == null) {
            return List.of();
        }
        return questions.activePool(userId, directionId).stream()
            .limit(properties.getQuestionCount())
            .toList();
    }

    /**
     * 面试官轮（异步）：在 aiIo 线程上流式生成 + 句级并发 TTS，帧经 sink 下发后返回。
     * 降级口径（voice-adr 修订 1 §4）：LLM 流内失败或通道未装配时，有 TTS 就读题干/结束语、
     * 没 TTS 就发字幕；两条分支都必须下发 text。
     *
     * @param questionIndex 当前题目下标（0 基；== 队列长度时生成结束语）
     * @param queue         题目队列快照
     */
    public void speakTurn(UUID sessionId, UUID userId, UUID directionId,
                          List<QuestionCandidate> queue, int questionIndex, TurnSink sink) {
        try {
            aiIo.execute(() -> doSpeakTurn(sessionId, userId, directionId, queue, questionIndex, sink));
        } catch (RejectedExecutionException e) {
            // ai-io 池是 AbortPolicy：饱和时本轮不能无声吞掉——否则 handler 的 speaking 永置
            // true，会话从此卡在“面试官发言中”（修订 1 §4 ④）。降级为直读题干的本轮终态。
            log.warn("[voice] ai-io 池饱和，面试官轮降级直读 session={}: {}", sessionId, e.toString());
            boolean closing = questionIndex >= queue.size();
            String stem = closing ? null : queue.get(questionIndex).question();
            deliver(sessionId, questionIdOf(queue, questionIndex), fallbackText(stem, closing),
                new AtomicBoolean(false), sink);
        }
    }

    private void doSpeakTurn(UUID sessionId, UUID userId, UUID directionId,
                             List<QuestionCandidate> queue, int questionIndex, TurnSink sink) {
        String directionName = directionName(userId, directionId);
        boolean closing = questionIndex >= queue.size();
        String stem = closing ? null : queue.get(questionIndex).question();
        UUID questionId = questionIdOf(queue, questionIndex);

        List<ChatMessage> prompt = buildMessages(sessionId, directionName, queue, questionIndex, stem);
        SentenceSplitter splitter = new SentenceSplitter();
        AtomicBoolean textSent = new AtomicBoolean(false);

        // 发射器缺席或自身装配失败（池饱和）都只丢音频，不连带丢字幕（修订 1 §4 ①）
        OrderedTtsChunkEmitter created = null;
        try {
            created = newEmitter(sink);
        } catch (RuntimeException e) {
            log.warn("[voice] TTS 发射器装配失败，本轮降级纯字幕 session={}: {}", sessionId, e.toString());
        }
        final OrderedTtsChunkEmitter emitter = created;

        try (UsageContext.Scope ignored = UsageContext.bind(userId.toString(), "VOICE",
            sessionId, null)) {
            chat.orElseThrow().streamChat(prompt, new ChatStreamListener() {
                @Override
                public void onDelta(String delta) {
                    if (emitter != null) {
                        splitter.feed(delta).forEach(emitter::submit);
                    }
                }

                @Override
                public void onComplete(String text, UsageInfo usage) {
                    if (emitter != null) {
                        splitter.flush().forEach(emitter::submit);
                    }
                    if (usage != null && usage.totalTokens() > 0) {
                        StreamingChatProvider provider = chat.orElseThrow();
                        usageLedger.record(new UsageLedger.UsageEntry(userId, "VOICE", sessionId,
                            provider.channel(), provider.name(), "chat",
                            usage.promptTokens(), usage.completionTokens(), null, null));
                    }
                    deliver(sessionId, questionId,
                        text == null || text.isBlank() ? fallbackText(stem, closing) : text,
                        textSent, sink);
                }

                @Override
                public void onError(Throwable cause) {
                    // 降级路径：LLM 不可用 → 直接朗读题干/结束语（ADR §决策 7）
                    log.warn("[voice] 面试官轮流式失败，降级朗读题干 session={}: {}",
                        sessionId, cause.toString());
                    String fallback = fallbackText(stem, closing);
                    if (emitter != null) {
                        emitter.submit(fallback);
                    }
                    deliver(sessionId, questionId, fallback, textSent, sink);
                }
            });
        } catch (RuntimeException e) {
            // 通道未装配（none）或 streamChat 本身抛出：题干/结束语直接兼输出（修订 1 §4 ②）
            log.warn("[voice] chat 通道不可用，本轮降级直读题干 session={}: {}", sessionId, e.toString());
            String fallback = fallbackText(stem, closing);
            if (emitter != null) {
                emitter.submit(fallback);
            }
            deliver(sessionId, questionId, fallback, textSent, sink);
        } finally {
            if (emitter != null) {
                // finish 必须在全部终态分支之后：漏掉它排空线程会白等满预算（修订 1 §4）
                emitter.finish();
                emitter.awaitCompletion();
            }
            // 兜底闸门：监听器一个回调都没到时也要下发一次 text，否则 handler 的
            // speaking 永置 true，候选人每次「回答完毕」都被拒死（修订 1 §4 ④）
            deliver(sessionId, questionId, fallbackText(stem, closing), textSent, sink);
        }
    }

    /** TTS 未装配（provider=none）时返回 null：本轮不发音频。 */
    private OrderedTtsChunkEmitter newEmitter(TurnSink sink) {
        TtsProvider provider = tts.orElse(null);
        if (provider == null) {
            return null;
        }
        return new OrderedTtsChunkEmitter(provider, TtsOptions.defaults(),
            properties.getTtsMaxConcurrent(), properties.getTtsChunkTimeoutSeconds(),
            (seq, wav, isLast) -> {
                // 空块只有一种合法用途：排空末块的 isLast 标记（客户端靠它上报 audio_done
                // 立即解除回声窗）；普通空句不下发
                if (wav.length > 0 || isLast) {
                    sink.onAudioChunk(seq, wav, isLast);
                }
            }, aiIo);
    }

    /**
     * 本轮终态下发：落 QUESTION 轮 + 发字幕，全局恰好一次（{@code textSent} CAS 守门）。
     * 落库失败只记日志不吞字幕——闸门释放优先于留痕，缺行在报告侧按会话读不到轮次自然体现。
     */
    private void deliver(UUID sessionId, UUID questionId, String content,
                         AtomicBoolean textSent, TurnSink sink) {
        if (!textSent.compareAndSet(false, true)) {
            return;
        }
        try {
            sessions.appendTurn(sessionId, VoiceSessionMessageEntity.ROLE_QUESTION, questionId, content);
        } catch (RuntimeException e) {
            log.warn("[voice] QUESTION 轮落库失败 session={}: {}", sessionId, e.toString());
        }
        sink.onText(content);
    }

    /** 题干朗读降级文案：有题读题，无题收尾。 */
    private String fallbackText(String stem, boolean closing) {
        if (closing || stem == null) {
            return "好，今天的面试就到这里，感谢你的时间。";
        }
        return "下面这个问题请你回答：" + stem;
    }

    private UUID questionIdOf(List<QuestionCandidate> queue, int index) {
        return index < queue.size() ? queue.get(index).id() : null;
    }

    /**
     * 面试官提示词（🅖 VoiceInterviewPromptService 的输出约束 + annona 题库队列口径）：
     * 题干保持原意是 P3-05 可比性的保证——LLM 只加过渡语，不改写问题内容。
     * 历史从 voice_message 装配（QUESTION→assistant，ANSWER→user），超
     * {@code historyMaxTurns} 截最旧——完整压缩器批 3（ADR 修订 1）。
     */
    List<ChatMessage> buildMessages(UUID sessionId, String directionName,
                                    List<QuestionCandidate> queue, int questionIndex, String stem) {
        StringBuilder system = new StringBuilder();
        system.append("你是一位").append(directionName == null ? "技术" : directionName)
            .append("方向的语音面试官。\n");
        system.append("【题目清单】\n");
        for (int i = 0; i < queue.size(); i++) {
            system.append(i + 1).append(". ").append(queue.get(i).question()).append('\n');
        }
        if (queue.isEmpty()) {
            system.append("（本次未加载题库，请进行自由技术问答。）\n");
        }
        if (questionIndex >= queue.size()) {
            system.append("【当前进度】所有题目已完成——请用两三句自然收尾，感谢候选人。\n");
        } else {
            system.append("【当前进度】请提出第 ").append(questionIndex + 1).append(" 题（共 ")
                .append(queue.size()).append(" 题），题干必须保持原意：").append(stem).append('\n');
        }
        system.append("""
            【语音面试输出约束】
            1. 只输出要说的话，2-4 句；不用列表、Markdown、代码块。
            2. 题干内容保持原意，前后可加一句自然过渡。
            3. 语气简洁直接，适配口语对话。""");

        List<VoiceSessionMessageEntity> turns = messages.findBySessionIdOrderBySeqAsc(sessionId);
        List<ChatMessage> history = turns.stream()
            .skip(Math.max(0, turns.size() - properties.getHistoryMaxTurns()))
            .map(m -> new ChatMessage(
                VoiceSessionMessageEntity.ROLE_QUESTION.equals(m.getRole()) ? "assistant" : "user",
                m.getContent()))
            .toList();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", system.toString()));
        messages.addAll(history);
        messages.add(new ChatMessage("user",
            questionIndex >= queue.size() ? "（候选人已答完全部题目，请收尾）" : "（候选人准备好了，请提下一题）"));
        return messages;
    }

    /** 方向名（报告归属与提示词用）；未绑定方向返回 null。 */
    private String directionName(UUID userId, UUID directionId) {
        if (directionId == null) {
            return null;
        }
        return directions.listVisible(userId.toString()).stream()
            .filter(d -> directionId.toString().equals(d.id()))
            .map(DirectionResponse::name)
            .findFirst()
            .orElse(null);
    }
}
