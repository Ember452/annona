package io.annona.modules.voice.handler;

import io.annona.common.model.StreamingChatProvider;
import io.annona.common.voice.AsrOptions;
import io.annona.common.voice.AsrListener;
import io.annona.common.voice.StreamingAsrProvider;
import io.annona.common.voice.TtsOptions;
import io.annona.common.voice.TtsProvider;
import io.annona.modules.voice.audio.EchoGate;
import io.annona.modules.voice.audio.PcmWav;
import io.annona.modules.voice.config.VoiceProperties;
import io.annona.modules.voice.dto.VoiceProtocol;
import io.annona.modules.voice.entity.VoiceSessionMessageEntity;
import io.annona.modules.voice.metrics.VoiceLatencyMetrics;
import io.annona.modules.voice.service.VoiceInterviewService;
import io.annona.modules.voice.service.VoiceSessionService;
import io.annona.shared.question.QuestionCandidate;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.HtmlUtils;

/**
 * /ws/voice 消息路由与会话编排（P3-01/03，voice-adr §决策 3/5 + 修订 1）。职责：协议
 * 分发、连接级状态、ASR 会话生命周期（一次重启恢复）、回声半双工（时间窗兜底 +
 * 客户端 audio_done 信号解禁）、迟到转写丢弃、开场白与面试官轮下发（LLM/TTS 编排
 * 委托 {@link VoiceInterviewService}，帧经本类下发）。持久化全部委托
 * {@link VoiceSessionService}（每方法一个短事务）；ASR/TTS/LLM 调用一律在事务外。
 *
 * <p>轮转模型（修订 1）：开场白 → 面试官逐题提问（LLM 流式 + 句级并发 TTS）→ 候选人
 * 语音/文字作答（VAD 定稿进答案缓冲）→ 「回答完毕」落 ANSWER 轮并推进题目 → … →
 * 最后一题答完 LLM 收尾 → 用户结束会话 → FINALIZED + 收口事件。
 *
 * <p>失败语义（客户端可见）：通道中断/帧不合法发 {@code error} 帧（码段 2600），
 * recoverable=true 表示降级或重试后可继续；ASR 通道中断后连接保持、转手动提交。
 * 日志不落用户转写文本（voice-adr 隐私红线，🅖 shouldNotLogSttText 同款规格）。
 */
@Component
public class VoiceWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);

    private static final int CODE_CHANNEL_NOT_CONFIGURED = 2601;
    private static final int CODE_CHANNEL_INTERRUPTED = 2602;
    private static final int CODE_BAD_FRAME = 2603;

    /** ASR 通道故障后的一次重启尝试上限：重启仍失败即降级手动提交（🅖 restart 先例的最小版）。 */
    private static final int ASR_RESTART_ATTEMPTS = 1;

    private final VoiceSessionService sessions;
    private final VoiceInterviewService interviews;
    private final VoiceProperties properties;
    private final VoiceLatencyMetrics metrics;
    private final Map<String, StreamingAsrProvider> asrProviders;
    private final Map<String, TtsProvider> ttsProviders;

    /** 连接级状态；键为 WS session id（连接唯一）。 */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(VoiceSessionService sessions, VoiceInterviewService interviews,
                                 VoiceProperties properties, VoiceLatencyMetrics metrics,
                                 Map<String, StreamingAsrProvider> asrProviders,
                                 Map<String, TtsProvider> ttsProviders) {
        this.sessions = sessions;
        this.interviews = interviews;
        this.properties = properties;
        this.metrics = metrics;
        // Optional 注入语义在装配侧完成：provider=none 时 bean 缺席，此处收到的 map 不含该通道
        this.asrProviders = asrProviders;
        this.ttsProviders = ttsProviders;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession wsSession) {
        Connection conn = new Connection(userIdOf(wsSession), wsSession);
        connections.put(wsSession.getId(), conn);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession wsSession, @NonNull CloseStatus status) {
        Connection conn = connections.remove(wsSession.getId());
        if (conn != null && conn.voiceSessionId != null) {
            conn.finalized.set(true);
            closeAsrQuietly(conn);
            sessions.abandonIfOpen(conn.voiceSessionId, conn.userId);
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession wsSession, @NonNull TextMessage message) {
        Connection conn = connections.get(wsSession.getId());
        if (conn == null) {
            return;
        }
        VoiceProtocol.ClientFrame frame;
        try {
            frame = VoiceProtocol.parse(message.getPayload());
        } catch (IOException e) {
            trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME, "帧格式不合法", true));
            return;
        }
        try {
            switch (frame.type()) {
                case "start" -> handleStart(conn, frame);
                case "audio" -> handleAudio(conn, frame);
                case "control" -> handleControl(conn, frame);
                case "submit" -> handleSubmit(conn, frame.text());
                case "ping" -> { /* 保活由 WS 协议层心跳承担；应用层 ping 静默容忍 */ }
                default -> trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME,
                    "未知帧类型: " + HtmlUtils.htmlEscape(frame.type()), true));
            }
        } catch (IOException e) {
            log.info("[voice] send failed (client likely gone): {}", e.toString());
        }
    }

    // ── start：建会话 + 题目队列 + 开场白 + 第一个面试官轮 ─────────────────

    private void handleStart(Connection conn, VoiceProtocol.ClientFrame frame) throws IOException {
        if (conn.voiceSessionId != null) {
            trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME, "会话已开始", true));
            return;
        }
        UUID directionId = frame.directionId() != null && !frame.directionId().isBlank()
            ? UUID.fromString(frame.directionId()) : null;

        StreamingAsrProvider asrProvider = asrProviders.isEmpty() ? null : asrProviders.values().iterator().next();
        TtsProvider ttsProvider = ttsProviders.isEmpty() ? null : ttsProviders.values().iterator().next();

        // 题目队列快照（V19）：开场从题库截取，重连按快照恢复
        List<QuestionCandidate> queue = interviews.loadQueue(conn.userId, directionId);
        String idsJson = queue.stream().map(q -> '"' + q.id().toString() + '"')
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        var session = sessions.create(conn.userId, directionId,
            asrProvider == null ? null : asrProvider.name(),
            ttsProvider == null ? null : ttsProvider.name(),
            idsJson, properties.getOpening());
        conn.voiceSessionId = session.getId();
        conn.directionId = directionId;
        conn.queue = queue;
        conn.currentQuestionSeq = 0;

        if (asrProvider != null) {
            openAsr(conn, asrProvider, 0);
        }
        send(conn, VoiceProtocol.state(session.getStatus()));
        speak(conn, ttsProvider, session.getOpening());

        // 第一个面试官轮（题 0；队列空则为自由问答轮）
        startInterviewerTurn(conn, 0);
    }

    /** 打开一路 ASR 会话；onReady 由 provider 回调触发 ready 帧。 */
    private void openAsr(Connection conn, StreamingAsrProvider asrProvider, int attempt) {
        try {
            conn.asr = asrProvider.start(AsrOptions.annonaDefault(), asrListener(conn));
        } catch (RuntimeException e) {
            log.warn("[voice] ASR start failed (attempt {}): {}", attempt, e.toString());
            trySend(conn, VoiceProtocol.error(CODE_CHANNEL_INTERRUPTED,
                "语音识别通道启动失败，请使用文字输入", true));
        }
    }

    /** ASR 回调适配：ready/partial/final/error → 下行帧 + 答案缓冲 + 埋点。 */
    private AsrListener asrListener(Connection conn) {
        return new AsrListener() {
            @Override
            public void onReady() {
                trySend(conn, VoiceProtocol.ready());
            }

            @Override
            public void onPartial(String text) {
                if (conn.finalized.get()) {
                    return; // 迟到 partial 丢弃
                }
                recordFirstPartialIfNeeded(conn);
                trySend(conn, VoiceProtocol.subtitle(text, false));
            }

            @Override
            public void onFinal(String text) {
                if (conn.finalized.get()) {
                    return; // 迟到定稿丢弃：不污染已收口会话（🅖 行为规格）
                }
                recordFirstPartialIfNeeded(conn);
                sessions.appendTranscript(conn.voiceSessionId, conn.userId, text);
                conn.answerBuffer.add(text);
                trySend(conn, VoiceProtocol.subtitle(text, true));
            }

            @Override
            public void onError(Throwable cause) {
                log.warn("[voice] ASR channel error: {}", cause.toString());
                degradeAsr(conn);
            }
        };
    }

    /** 首个 partial 埋点（每会话一次）：首帧上行音频 → 首个 partial。 */
    private void recordFirstPartialIfNeeded(Connection conn) {
        long firstAudio = conn.firstAudioAt.get();
        if (firstAudio > 0 && conn.firstPartialRecorded.compareAndSet(false, true)) {
            metrics.recordAsrFirstPartial(Duration.ofMillis(System.currentTimeMillis() - firstAudio));
        }
    }

    // ── audio：回声闸门 + 暂停丢弃 + ASR 故障重启 ──────────────────────────

    private void handleAudio(Connection conn, VoiceProtocol.ClientFrame frame) {
        if (conn.voiceSessionId == null || conn.asr == null || conn.paused.get()) {
            return; // 未开始/降级/暂停：静默丢帧（暂停是主动行为，不该给用户红条）
        }
        if (frame.data() == null || frame.data().length() > VoiceProtocol.MAX_FRAME_CHARS) {
            trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME, "音频帧超限", true));
            return;
        }
        if (conn.echoGate.isMuted(Instant.now())) {
            return; // AI 播放期半双工：丢弃防自我循环
        }
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(frame.data());
        } catch (IllegalArgumentException e) {
            trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME, "音频帧不是合法 base64", true));
            return;
        }
        conn.firstAudioAt.compareAndSet(0L, System.currentTimeMillis());
        try {
            conn.asr.sendAudio(pcm);
        } catch (IllegalStateException e) {
            restartAsr(conn);
        }
    }

    /** 通道故障的恢复路径：静默重启（用户无感）；重启仍失败才降级手动提交。 */
    private void restartAsr(Connection conn) {
        closeAsrQuietly(conn);
        var provider = asrProviders.isEmpty() ? null : asrProviders.values().iterator().next();
        if (provider == null || conn.asrRestarts.get() >= ASR_RESTART_ATTEMPTS) {
            degradeAsr(conn);
            return;
        }
        conn.asrRestarts.incrementAndGet();
        openAsr(conn, provider, conn.asrRestarts.get());
    }

    /** 降级：ASR 通道置空，连接保持，客户端切手动提交（P3 出口③的 ASR 侧）。 */
    private void degradeAsr(Connection conn) {
        closeAsrQuietly(conn);
        trySend(conn, VoiceProtocol.error(CODE_CHANNEL_INTERRUPTED,
            "语音识别通道中断，已切换为文字输入", true));
    }

    // ── control / submit ─────────────────────────────────────────────────

    private void handleControl(Connection conn, VoiceProtocol.ClientFrame frame) throws IOException {
        String action = frame.action() == null ? "" : frame.action();
        switch (action) {
            case "pause" -> {
                if (sessions.pause(conn.voiceSessionId, conn.userId)) {
                    conn.paused.set(true);
                    send(conn, VoiceProtocol.state("PAUSED"));
                }
            }
            case "resume" -> {
                if (sessions.resume(conn.voiceSessionId, conn.userId)) {
                    conn.paused.set(false);
                    send(conn, VoiceProtocol.state("ACTIVE"));
                }
            }
            case "submit" -> finishAnswerTurn(conn, null);
            case "audio_done" -> conn.echoGate.reset(); // 客户端播放队列排空：立即解禁上行
            case "stop" -> {
                conn.finalized.set(true);
                closeAsrQuietly(conn);
                if (sessions.finalizeSession(conn.voiceSessionId, conn.userId)) {
                    send(conn, VoiceProtocol.state("FINALIZED"));
                }
                conn.wsSession.close(CloseStatus.NORMAL);
            }
            default -> trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME,
                "未知 control action: " + HtmlUtils.htmlEscape(action), true));
        }
    }

    /**
     * 键入作答：文字进答案缓冲并立即收本轮（键入即视为答完——语音答案靠
     * 「回答完毕」，文字答案以提交为界）。
     */
    private void handleSubmit(Connection conn, String text) throws IOException {
        if (conn.voiceSessionId == null) {
            trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME, "会话未开始", true));
            return;
        }
        if (text != null && !text.isBlank()) {
            conn.answerBuffer.add(text.trim());
            trySend(conn, VoiceProtocol.subtitle(text.trim(), true));
        }
        finishAnswerTurn(conn, text);
    }

    /**
     * 收本轮作答：缓冲 → ANSWER 轮落库（关联当前题）→ 推进题目 → 触发下一个面试官轮
     * （异步，帧随后到达）。缓冲为空且无文字 → 忽略（空轮不落库不推进）。
     */
    private void finishAnswerTurn(Connection conn, String typedText) throws IOException {
        if (conn.voiceSessionId == null) {
            trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME, "会话未开始", true));
            return;
        }
        if (conn.speaking.get()) {
            trySend(conn, VoiceProtocol.error(CODE_BAD_FRAME, "面试官发言中，请稍候", true));
            return;
        }
        String answer = String.join("\n", conn.answerBuffer);
        if (answer.isBlank()) {
            return;
        }
        int idx = conn.currentQuestionSeq;
        UUID questionId = idx < conn.queue.size() ? conn.queue.get(idx).id() : null;
        sessions.appendTurn(conn.voiceSessionId, VoiceSessionMessageEntity.ROLE_ANSWER, questionId, answer);
        sessions.advanceQuestionSeq(conn.voiceSessionId, conn.userId, idx + 1);
        conn.currentQuestionSeq = idx + 1;
        conn.answerBuffer.clear();
        startInterviewerTurn(conn, idx + 1);
    }

    /** 触发一个面试官轮（异步；text/audio 帧经 turnSink 下发）。 */
    private void startInterviewerTurn(Connection conn, int questionIndex) {
        conn.speaking.set(true);
        interviews.speakTurn(conn.voiceSessionId, conn.userId, conn.directionId,
            conn.queue, questionIndex, new VoiceInterviewService.TurnSink() {
                @Override
                public void onText(String content) {
                    conn.speaking.set(false);
                    trySend(conn, VoiceProtocol.text(content));
                }

                @Override
                public void onAudioChunk(int seq, byte[] wav, boolean isLast) {
                    // 空音频 = 本轮末块标记（只带 isLast）：不下发 WAV、不延长回声窗
                    if (wav.length == 0) {
                        if (isLast) {
                            trySend(conn, VoiceProtocol.audioChunk("", seq + 1, true));
                        }
                        return;
                    }
                    conn.echoGate.markSpeaking(wav.length, 24000, Instant.now());
                    trySend(conn, VoiceProtocol.audioChunk(
                        Base64.getEncoder().encodeToString(PcmWav.wrap(wav, 24000)), seq + 1, isLast));
                }
            });
    }

    // ── 开场白播报：TTS 可用走音频（半双工窗），不可用/失败走字幕 ─────────

    private void speak(Connection conn, TtsProvider ttsProvider, String text) throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        if (ttsProvider == null) {
            send(conn, VoiceProtocol.text(text));
            return;
        }
        try {
            long start = System.currentTimeMillis();
            byte[] pcm = ttsProvider.synthesize(text, TtsOptions.defaults());
            metrics.recordTtsSynthesize(Duration.ofMillis(System.currentTimeMillis() - start));
            if (pcm.length == 0) {
                send(conn, VoiceProtocol.text(text));
                return;
            }
            conn.echoGate.markSpeaking(pcm.length, 24000, Instant.now());
            String wav = Base64.getEncoder().encodeToString(PcmWav.wrap(pcm, 24000));
            send(conn, VoiceProtocol.audioChunk(wav, 1, true));
            send(conn, VoiceProtocol.text(text));
        } catch (RuntimeException e) {
            log.warn("[voice] TTS failed, falling back to subtitle: {}", e.toString());
            send(conn, VoiceProtocol.text(text));
        }
    }

    // ── 发送与清理 ───────────────────────────────────────────────────────

    private void send(Connection conn, String json) throws IOException {
        synchronized (conn.sendLock) {
            conn.wsSession.sendMessage(new TextMessage(json));
        }
    }

    private void trySend(Connection conn, String json) {
        try {
            send(conn, json);
        } catch (IOException e) {
            log.info("[voice] send failed (client likely gone): {}", e.toString());
        }
    }

    private void closeAsrQuietly(Connection conn) {
        var conversation = conn.asr;
        conn.asr = null;
        if (conversation != null) {
            try {
                conversation.close();
            } catch (RuntimeException e) {
                log.debug("[voice] ASR close ignored: {}", e.toString());
            }
        }
    }

    /**
     * 握手属性里的主体 id 是 {@link io.annona.spi.dto.Principal#id()} 的<b>字符串</b>形态
     * （local/none 模式即 app_user.id 的 UUID 文本；CI docker-it 走 local）。platform 模式
     * 的 unionId 非 UUID，本批语音链路不支持——解析失败在这里显式炸掉连接，而不是让
     * 后续每条 SQL 拿着非法 id 撞约束。
     */
    private UUID userIdOf(WebSocketSession wsSession) {
        Object userId = wsSession.getAttributes().get(VoiceHandshakeInterceptor.ATTR_USER_ID);
        try {
            return UUID.fromString(String.valueOf(userId));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("voice: 握手主体不是合法用户 id（platform 模式暂不支持语音）", e);
        }
    }

    /**
     * 连接级状态（一 WS 连接一路）。字段单线程写（WS 消息线程）+ 多线程读（aiIo 上的
     * 面试官轮回调），volatile/atomic 兜底。
     */
    private static final class Connection {
        final UUID userId;
        final EchoGate echoGate = new EchoGate();
        final AtomicBoolean paused = new AtomicBoolean(false);
        final AtomicBoolean finalized = new AtomicBoolean(false);
        final AtomicBoolean firstPartialRecorded = new AtomicBoolean(false);
        /** 面试官轮进行中（LLM 流式 + TTS 下发未完）：期间 submit 被拒。 */
        final AtomicBoolean speaking = new AtomicBoolean(false);
        /** 首帧上行音频的毫秒时间戳（0 = 尚无）；partial 埋点起点。 */
        final java.util.concurrent.atomic.AtomicLong firstAudioAt = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger asrRestarts = new java.util.concurrent.atomic.AtomicInteger();
        /** 本轮作答缓冲：VAD 定稿与键入文字按序累积，「回答完毕」时整轮落库。 */
        final List<String> answerBuffer = new ArrayList<>();
        final Object sendLock = new Object();
        final WebSocketSession wsSession;
        volatile UUID voiceSessionId;
        volatile UUID directionId;
        volatile List<QuestionCandidate> queue = List.of();
        volatile int currentQuestionSeq;
        volatile StreamingAsrProvider.AsrConversation asr;

        Connection(UUID userId, WebSocketSession wsSession) {
            this.userId = userId;
            this.wsSession = wsSession;
        }
    }
}
