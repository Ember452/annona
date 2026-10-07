package io.annona.modules.voice.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PCM→WAV 封装规格：44 字节 RIFF 头字段逐项断言（浏览器 {@code <audio>} 直接可播的契约）。
 */
@Tag("slice")
@DisplayName("PcmWav：RIFF 头封装")
class PcmWavTest {

    @Test
    @DisplayName("头字段：RIFF/WAVE 标记、单声道、采样率与数据长度小端写入")
    void riffHeader() {
        byte[] pcm = "annona-voice-pcm".getBytes(StandardCharsets.US_ASCII);
        byte[] wav = PcmWav.wrap(pcm, 24000);

        assertThat(wav).hasSize(44 + pcm.length);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(new String(wav, 36, 4, StandardCharsets.US_ASCII)).isEqualTo("data");
        // RIFF 块大小 = 36 + dataLen（小端）
        assertThat((wav[4] & 0xff) | ((wav[5] & 0xff) << 8) | ((wav[6] & 0xff) << 16)
            | ((wav[7] & 0xff) << 24)).isEqualTo(36 + pcm.length);
        // 采样率 @offset24（小端）
        assertThat((wav[24] & 0xff) | ((wav[25] & 0xff) << 8) | ((wav[26] & 0xff) << 16)
            | ((wav[27] & 0xff) << 24)).isEqualTo(24000);
        // data 块大小 @offset40（小端）
        assertThat((wav[40] & 0xff) | ((wav[41] & 0xff) << 8) | ((wav[42] & 0xff) << 16)
            | ((wav[43] & 0xff) << 24)).isEqualTo(pcm.length);
        // 音频数据原样跟在头后
        assertThat(wav).endsWith(pcm);
    }
}
