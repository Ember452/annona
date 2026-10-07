package io.annona.modules.voice.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 回声闸门规格（voice-adr：播放期半双工）：4800 字节 @24kHz = 100ms 音频，
 * 安全窗 = 100×1.5 + 300 = 450ms。纯时钟注入，无 sleep。
 */
@Tag("slice")
@DisplayName("EchoGate：AI 播放期丢弃麦克风上行")
class EchoGateTest {

    private static final Instant T0 = Instant.parse("2026-10-06T08:00:00Z");

    @Test
    @DisplayName("未播报时不静音；播报期与冷却期内静音；窗口过后解禁")
    void muteWindow() {
        var gate = new EchoGate();
        assertThat(gate.isMuted(T0)).isFalse();

        gate.markSpeaking(4800, 24000, T0); // 100ms 音频 → 150% 安全窗 + 300ms 冷却 = 450ms
        assertThat(gate.isMuted(T0.plusMillis(100))).isTrue();
        assertThat(gate.isMuted(T0.plusMillis(449))).isTrue();
        assertThat(gate.isMuted(T0.plusMillis(450))).isFalse();
    }

    @Test
    @DisplayName("重叠播报取最晚解禁点（长句不会被短句提前解禁）")
    void overlappingMarksExtend() {
        var gate = new EchoGate();
        gate.markSpeaking(4800, 24000, T0);                    // 解禁 T0+450ms
        gate.markSpeaking(48000, 24000, T0.plusMillis(100));   // 1s 音频 → 解禁 T0+100+1800=1900ms
        assertThat(gate.isMuted(T0.plusMillis(460))).isTrue();
        assertThat(gate.isMuted(T0.plusMillis(1900))).isFalse();
    }
}
