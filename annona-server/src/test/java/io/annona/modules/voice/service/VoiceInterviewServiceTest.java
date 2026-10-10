package io.annona.modules.voice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.annona.common.model.ChatMessage;
import io.annona.common.model.ChatStreamListener;
import io.annona.common.model.StreamingChatProvider;
import io.annona.common.usage.UsageLedger;
import io.annona.common.voice.TtsOptions;
import io.annona.common.voice.TtsProvider;
import io.annona.modules.voice.config.VoiceProperties;
import io.annona.modules.voice.entity.VoiceSessionMessageEntity;
import io.annona.modules.voice.repository.VoiceSessionMessageRepository;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.question.QuestionCandidate;
import io.annona.shared.question.QuestionQueryService;
import io.annona.spi.dto.UsageInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 面试官轮的降级规格（P3 出口③，voice-adr 修订 1 §4）。钉四件事：
 * ① TTS 通道缺席时仍下发 {@code text} 帧（纯字幕），不得让整轮静默；
 * ② LLM 通道缺席时直读题干；
 * ③ 任何异常分支都恰好下发一次 {@code text}——handler 的 {@code speaking} 闸门由
 *    onText 释放，漏发或双发都会让会话卡死在「面试官发言中」；
 * ④ 未走完流终态时发射器必须被 finish（否则排空线程白等一整个预算）。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("VoiceInterviewService：降级路径恰好一次字幕")
class VoiceInterviewServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final QuestionCandidate Q0 =
        new QuestionCandidate(UUID.randomUUID(), "什么是事务传播行为？", 3, 0);

    @Mock
    private VoiceSessionService sessions;
    @Mock
    private VoiceSessionMessageRepository messages;
    @Mock
    private QuestionQueryService questions;
    @Mock
    private DirectionQueryService directions;
    @Mock
    private UsageLedger usageLedger;

    private VoiceProperties properties;
    private ExecutorService aiIo;

    /** 记录下发帧的 sink；latch 让异步轮回可等待。 */
    private static final class Sink implements VoiceInterviewService.TurnSink {
        final List<String> texts = new ArrayList<>();
        final List<Integer> audioChunks = new ArrayList<>();
        final CountDownLatch text = new CountDownLatch(1);

        @Override
        public void onText(String content) {
            texts.add(content);
            text.countDown();
        }

        @Override
        public void onAudioChunk(int seq, byte[] wav, boolean isLast) {
            audioChunks.add(seq);
        }
    }

    @BeforeEach
    void setUp() {
        properties = new VoiceProperties();
        properties.setQuestionCount(5);
        properties.setTtsMaxConcurrent(2);
        properties.setTtsChunkTimeoutSeconds(1);
        properties.setHistoryMaxTurns(20);
        // 发射器在同一个池上跑排空与合成，单线程会自锁（OrderedTtsChunkEmitterTest 同口径）
        aiIo = Executors.newFixedThreadPool(4);
        // 历史装配的读：降级/拒绝分支不走到这里，故 lenient
        lenient().when(messages.findBySessionIdOrderBySeqAsc(SESSION_ID)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        aiIo.shutdownNow();
    }

    private VoiceInterviewService service(Optional<StreamingChatProvider> chat,
                                           Optional<TtsProvider> tts) {
        return new VoiceInterviewService(sessions, messages, questions, directions,
            chat, tts, usageLedger, properties, aiIo);
    }

    private static TtsProvider stubTts() {
        return new TtsProvider() {
            @Override
            public String name() {
                return "stub-tts";
            }

            @Override
            public String channel() {
                return "stub";
            }

            @Override
            public byte[] synthesize(String text, TtsOptions options) {
                return new byte[64];
            }
        };
    }

    /** 按脚本行逐增量、正常终态的 chat 桩。 */
    private static StreamingChatProvider stubChat(String... deltas) {
        return new StreamingChatProvider() {
            @Override
            public String name() {
                return "stub-chat";
            }

            @Override
            public String channel() {
                return "stub";
            }

            @Override
            public long streamTimeoutMillis() {
                return 0;
            }

            @Override
            public void streamChat(List<ChatMessage> msgs, ChatStreamListener listener) {
                StringBuilder all = new StringBuilder();
                for (String d : deltas) {
                    all.append(d);
                    listener.onDelta(d);
                }
                listener.onComplete(all.toString(), new UsageInfo(10, 5));
            }
        };
    }

    /** 调用即抛的 chat 桩（模拟通道装配后连接失败）。 */
    private static StreamingChatProvider throwingChat() {
        return new StreamingChatProvider() {
            @Override
            public String name() {
                return "stub-chat";
            }

            @Override
            public String channel() {
                return "stub";
            }

            @Override
            public long streamTimeoutMillis() {
                return 0;
            }

            @Override
            public void streamChat(List<ChatMessage> msgs, ChatStreamListener listener) {
                throw new IllegalStateException("channel down");
            }
        };
    }

    private Sink awaitOneTurn(VoiceInterviewService service) throws InterruptedException {
        Sink sink = new Sink();
        service.speakTurn(SESSION_ID, USER_ID, null, List.of(Q0), 0, sink);
        assertThat(sink.text.await(5, TimeUnit.SECONDS))
            .as("面试官轮必须在预算内下发字幕").isTrue();
        return sink;
    }

    @Test
    @DisplayName("TTS 通道缺席：仍下发字幕且落 QUESTION 轮，不发音频")
    void speaksSubtitleWhenTtsAbsent() throws Exception {
        var service = service(Optional.of(stubChat("你好，", "请回答：", Q0.question())), Optional.empty());

        var sink = awaitOneTurn(service);

        assertThat(sink.texts).hasSize(1);
        assertThat(sink.texts.get(0)).contains(Q0.question());
        assertThat(sink.audioChunks).isEmpty();
        verify(sessions).appendTurn(SESSION_ID, VoiceSessionMessageEntity.ROLE_QUESTION,
            Q0.id(), sink.texts.get(0));
    }

    @Test
    @DisplayName("LLM 通道缺席：直接朗读题干文案，字幕恰好一次")
    void fallsBackToStemWhenChatAbsent() throws Exception {
        var service = service(Optional.empty(), Optional.of(stubTts()));

        var sink = awaitOneTurn(service);

        assertThat(sink.texts).hasSize(1);
        assertThat(sink.texts.get(0)).contains(Q0.question());
        verify(sessions).appendTurn(eq(SESSION_ID), eq(VoiceSessionMessageEntity.ROLE_QUESTION),
            eq(Q0.id()), any());
    }

    @Test
    @DisplayName("streamChat 抛出：字幕恰好一次，且不等满排空预算")
    void streamChatThrowingReleasesTurnPromptly() throws Exception {
        var service = service(Optional.of(throwingChat()), Optional.of(stubTts()));
        Sink sink = new Sink();

        long start = System.nanoTime();
        service.speakTurn(SESSION_ID, USER_ID, null, List.of(Q0), 0, sink);
        assertThat(sink.text.await(3, TimeUnit.SECONDS))
            .as("降级字幕必须在 3s 内下发（发射器未 finish 会白等满预算）").isTrue();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("降级耗时 %d ms".formatted(elapsedMs)).isLessThan(3000);
        assertThat(sink.texts).hasSize(1);
        verify(sessions, times(1)).appendTurn(eq(SESSION_ID),
            eq(VoiceSessionMessageEntity.ROLE_QUESTION), eq(Q0.id()), any());
    }

    @Test
    @DisplayName("LLM 空输出：按题干兜底，不落空字幕")
    void blankLlmOutputFallsBackToStem() throws Exception {
        var service = service(Optional.of(stubChat("  ")), Optional.empty());

        var sink = awaitOneTurn(service);

        assertThat(sink.texts).hasSize(1);
        assertThat(sink.texts.get(0)).isNotBlank().contains(Q0.question());
    }

    @Test
    @DisplayName("ai-io 池饱和（AbortPolicy）：本轮仍下发字幕，不留永久沉默")
    void rejectedTurnStillDeliversText() {
        var sink = new Sink();
        var service = new VoiceInterviewService(sessions, messages, questions, directions,
            Optional.of(stubChat("喂")), Optional.empty(), usageLedger, properties,
            task -> {
                throw new RejectedExecutionException("pool full");
            });

        service.speakTurn(SESSION_ID, USER_ID, null, List.of(Q0), 0, sink);

        assertThat(sink.texts).as("拒绝时同步降级，不排队等死").hasSize(1);
        assertThat(sink.texts.get(0)).contains(Q0.question());
    }
}
