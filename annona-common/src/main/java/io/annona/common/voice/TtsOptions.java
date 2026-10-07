package io.annona.common.voice;

/**
 * TTS 会话级参数（voice-adr §决策 1）。供应商级配置（模型/Key/默认音色/语速）在各自
 * Properties；端口只承载可按次覆盖的量。
 *
 * @param voice 音色名（如 Cherry）；null = 实现默认音色
 */
public record TtsOptions(String voice) {

    /** 空参数 = 全部走实现默认（读侧代码少一层 null 分支）。 */
    public static TtsOptions defaults() {
        return new TtsOptions(null);
    }
}
