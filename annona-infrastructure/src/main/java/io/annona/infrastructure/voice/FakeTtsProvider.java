package io.annona.infrastructure.voice;

import io.annona.common.voice.TtsOptions;
import io.annona.common.voice.TtsProvider;

/**
 * 确定性 TTS fake（voice-adr §决策 2）：返回固定形状的可播 PCM（24kHz/16bit/单声道、
 * 100ms 440Hz 低幅正弦——前端可听到短提示音，链路可测），<b>不可读作合成质量</b>。
 * 字节数确定性：4800 字节/次，VoiceFlowIT 可断言。
 */
public class FakeTtsProvider implements TtsProvider {

    /** 采样率与端口契约一致（24kHz）。 */
    static final int SAMPLE_RATE = 24000;
    /** 100ms → 2400 样本 → 4800 字节（16bit）。 */
    static final int BYTES = 4800;

    @Override
    public String name() {
        return "fake-tts";
    }

    @Override
    public String channel() {
        return "fake";
    }

    @Override
    public byte[] synthesize(String text, TtsOptions options) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }
        byte[] pcm = new byte[BYTES];
        for (int i = 0; i < BYTES / 2; i++) {
            // 440Hz 正弦、约 20% 满幅：人耳可闻，数值确定性
            double angle = 2 * Math.PI * 440 * i / SAMPLE_RATE;
            short sample = (short) (Math.sin(angle) * Short.MAX_VALUE * 0.2);
            pcm[2 * i] = (byte) (sample & 0xff);
            pcm[2 * i + 1] = (byte) ((sample >> 8) & 0xff);
        }
        return pcm;
    }
}
