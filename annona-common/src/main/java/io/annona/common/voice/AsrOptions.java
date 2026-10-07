package io.annona.common.voice;

import java.util.Objects;

/**
 * ASR 会话级参数（voice-adr §决策 1）。供应商级配置（模型/Key/VAD 形状）在各自
 * Properties 里，不进端口——端口只承载"会话与会议间可能不同"的量。
 *
 * @param language   识别语言（BCP-47 风格，如 zh）；null = 实现默认
 * @param sampleRate 上行采样率（Hz）；当前实现契约固定 16000，显式化是为了未来多采样率
 */
public record AsrOptions(String language, int sampleRate) {

    public AsrOptions {
        Objects.requireNonNull(language, "language");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
    }

    /** annona 当前音频链路的固定形状（前端 AudioWorklet 采集口径）。 */
    public static AsrOptions annonaDefault() {
        return new AsrOptions("zh", 16000);
    }
}
