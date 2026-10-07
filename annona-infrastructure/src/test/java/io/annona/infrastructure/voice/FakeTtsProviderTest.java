package io.annona.infrastructure.voice;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.voice.TtsOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fake TTS 确定性规格：空白文本返空数组（端口契约）、有效文本固定 4800 字节可播 PCM、
 * 逐字节确定性（VoiceFlowIT 可断言）。
 */
@Tag("slice")
@DisplayName("FakeTtsProvider：确定性 PCM 输出")
class FakeTtsProviderTest {

    private final FakeTtsProvider provider = new FakeTtsProvider();

    @Test
    @DisplayName("null/空白文本返回空数组（正常分支不抛异常）")
    void blankReturnsEmpty() {
        assertThat(provider.synthesize(null, TtsOptions.defaults())).isEmpty();
        assertThat(provider.synthesize("   ", TtsOptions.defaults())).isEmpty();
    }

    @Test
    @DisplayName("有效文本固定 4800 字节且逐次一致（确定性）")
    void deterministicBytes() {
        byte[] first = provider.synthesize("你好", TtsOptions.defaults());
        byte[] second = provider.synthesize("你好", TtsOptions.defaults());
        assertThat(first).hasSize(FakeTtsProvider.BYTES);
        assertThat(first).isEqualTo(second);
    }
}
