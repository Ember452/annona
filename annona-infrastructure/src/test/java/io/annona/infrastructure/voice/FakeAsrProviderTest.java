package io.annona.infrastructure.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.common.voice.AsrListener;
import io.annona.common.voice.AsrOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fake ASR 确定性脚本规格（批 1 CI/本机全链路的地基）：VoiceFlowIT 依赖
 * "两帧一句"的节奏与 onReady 同步触发两条行为。
 */
@Tag("slice")
@DisplayName("FakeAsrProvider：确定性两帧一句脚本")
class FakeAsrProviderTest {

    /** 回调收集器（单线程同步触发，无需并发容器）。 */
    private static final class Recording implements AsrListener {
        final List<String> partials = new ArrayList<>();
        final List<String> finals = new ArrayList<>();
        int readyCount;

        @Override
        public void onReady() {
            readyCount++;
        }

        @Override
        public void onPartial(String text) {
            partials.add(text);
        }

        @Override
        public void onFinal(String text) {
            finals.add(text);
        }

        @Override
        public void onError(Throwable cause) {
            throw new AssertionError("fake 脚本不应触发 onError", cause);
        }
    }

    @Test
    @DisplayName("onReady 在 start 时同步恰好一次；两帧出一句（partial → final）")
    void twoFramesPerSentence() {
        var provider = new FakeAsrProvider();
        var listener = new Recording();

        var conversation = provider.start(AsrOptions.annonaDefault(), listener);
        assertThat(listener.readyCount).isEqualTo(1);

        conversation.sendAudio(new byte[16]);
        assertThat(listener.partials).hasSize(1);
        assertThat(listener.finals).isEmpty();

        conversation.sendAudio(new byte[16]);
        assertThat(listener.finals).hasSize(1);
        assertThat(listener.finals.get(0)).contains("第1句");

        conversation.sendAudio(new byte[16]);
        assertThat(listener.partials).hasSize(2);
        conversation.sendAudio(new byte[16]);
        assertThat(listener.finals).hasSize(2);
        assertThat(listener.finals.get(1)).contains("第2句");
    }

    @Test
    @DisplayName("close 后 sendAudio 抛 IllegalStateException（端口契约：故障抛出交编排层）")
    void rejectsSendAfterClose() {
        var provider = new FakeAsrProvider();
        var conversation = provider.start(AsrOptions.annonaDefault(), new Recording());
        conversation.close();
        assertThatThrownBy(() -> conversation.sendAudio(new byte[16]))
            .isInstanceOf(IllegalStateException.class);
    }
}
