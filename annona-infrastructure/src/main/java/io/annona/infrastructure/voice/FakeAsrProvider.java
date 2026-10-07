package io.annona.infrastructure.voice;

import io.annona.common.voice.AsrListener;
import io.annona.common.voice.AsrOptions;
import io.annona.common.voice.StreamingAsrProvider;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性 ASR fake（voice-adr §决策 2）：无 Key 环境跑通 WS→字幕→落库全链路，
 * <b>内容与节奏不可读作识别质量</b>。
 *
 * <p>脚本（按 sendAudio 帧数确定性驱动）：每"句"两帧——第 1 帧触发 onPartial，
 * 第 2 帧触发 onFinal（句号自增）；onReady 在 start 时同步触发。回调在调用线程
 * 同步触发（fake 无独立通道线程），VoiceFlowIT 据此写确定性断言。
 */
public class FakeAsrProvider implements StreamingAsrProvider {

    /** 每"句"的固定帧数：第 1 帧 partial，第 2 帧 final。 */
    static final int FRAMES_PER_SENTENCE = 2;

    private final AtomicInteger sessions = new AtomicInteger();

    @Override
    public String name() {
        return "fake-asr";
    }

    @Override
    public String channel() {
        return "fake";
    }

    @Override
    public AsrConversation start(AsrOptions options, AsrListener listener) {
        return new FakeConversation(sessions.incrementAndGet(), listener);
    }

    private static final class FakeConversation implements AsrConversation {

        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger framesSinceFinal = new AtomicInteger();
        private final AtomicInteger sentence = new AtomicInteger();
        private final int sessionId;
        private final AsrListener listener;

        FakeConversation(int sessionId, AsrListener listener) {
            this.sessionId = sessionId;
            this.listener = listener;
            listener.onReady();
        }

        @Override
        public void sendAudio(byte[] pcm) {
            if (closed.get()) {
                throw new IllegalStateException("fake ASR conversation already closed");
            }
            if (framesSinceFinal.incrementAndGet() == FRAMES_PER_SENTENCE) {
                framesSinceFinal.set(0);
                listener.onFinal("（fake 第" + sentence.incrementAndGet() + "句）会话 " + sessionId
                    + " 的确定性转写文本。");
            } else {
                listener.onPartial("（fake）正在识别第" + (sentence.get() + 1) + "句……");
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
