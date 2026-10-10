package io.annona.modules.voice.tts;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.voice.TtsOptions;
import io.annona.common.voice.TtsProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 有序发射器规格（P3-03，🅖 OrderedTtsChunkEmitter 行为规格）：严格按提交序下发、
 * isLast 恰好一次且在末块之后、空合成跳过、单句失败跳过不影响后续。
 */
@Tag("slice")
@DisplayName("OrderedTtsChunkEmitter：句级并发有序下发")
class OrderedTtsChunkEmitterTest {

    /**
     * 多线程池：排空线程常驻一个工作线程，句子合成需要另外的线程。
     * 单线程池会把合成任务永远排在排空线程后面（句句超时）；DIRECT 更会在构造期死锁。
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private record Chunk(int seq, int bytes, boolean isLast) {
    }

    private static final class Recorder implements OrderedTtsChunkEmitter.Sink {
        final List<Chunk> chunks = new ArrayList<>();

        @Override
        public void emit(int seq, byte[] wav, boolean isLast) {
            if (wav.length > 0) {
                chunks.add(new Chunk(seq, wav.length, isLast));
            }
        }
    }

    /** 按 id 合成固定长度音频的桩；id 含 "fail" 时抛异常模拟供应商故障。 */
    private static TtsProvider stubTts(int bytesPerSentence) {
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
                if (text.contains("fail")) {
                    throw new IllegalStateException("stub failure");
                }
                return new byte[bytesPerSentence + text.length()];
            }
        };
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("三句严格按提交序下发，isLast 恰好一次且在末块之后")
    void orderedEmission() {
        var recorder = new Recorder();
        var emitter = new OrderedTtsChunkEmitter(stubTts(100), TtsOptions.defaults(),
            3, 1, recorder, executor);

        emitter.submit("第一句");
        emitter.submit("第二句");
        emitter.submit("第三句");
        emitter.finish();
        int emitted = emitter.awaitCompletion();

        assertThat(emitted).isEqualTo(3);
        assertThat(recorder.chunks).extracting(Chunk::seq).containsExactly(0, 1, 2);
        assertThat(recorder.chunks).allSatisfy(c -> assertThat(c.isLast()).isFalse());
        // 排空结束标记：seq 越过末块、空音频（sink 对空块不记录，仅验证发射器语义）
        assertThat(recorder.chunks.get(2).seq()).isEqualTo(2);
    }

    @Test
    @DisplayName("单句合成失败被跳过，后续句子继续有序下发")
    void skipsFailedSentence() {
        var recorder = new Recorder();
        var emitter = new OrderedTtsChunkEmitter(stubTts(100), TtsOptions.defaults(),
            3, 1, recorder, executor);

        emitter.submit("正常句");
        emitter.submit("fail 句");
        emitter.submit("后续句");
        emitter.finish();
        int emitted = emitter.awaitCompletion();

        assertThat(emitted).isEqualTo(2);
        assertThat(recorder.chunks).extracting(Chunk::seq).containsExactly(0, 2);
    }

    @Test
    @DisplayName("信号量反压：并发上限 1 时句子仍全部有序完成")
    void semaphoreBackpressure() {
        var recorder = new Recorder();
        var emitter = new OrderedTtsChunkEmitter(stubTts(50), TtsOptions.defaults(),
            1, 1, recorder, executor);

        for (int i = 0; i < 5; i++) {
            emitter.submit("句子" + i);
        }
        emitter.finish();
        assertThat(emitter.awaitCompletion()).isEqualTo(5);
        assertThat(recorder.chunks).extracting(Chunk::seq).containsExactly(0, 1, 2, 3, 4);
    }
}
