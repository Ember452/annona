package io.annona.common.voice;

/**
 * 一次性 TTS 端口（voice-adr §决策 1）：一段文本进、一段音频出。句子级并发、按序推送、
 * 首句优先都是 voice 编排层的调度职责——端口刻意保持无状态单次合成，与 🅖
 * QwenTtsService.synthesize 同语义（临时建连 → 合成 → 收集 → 关闭）。
 *
 * <p>契约：null/空白文本返回空数组（不抛异常——上游没有可合成的内容是正常分支）；
 * 供应商故障抛 {@link RuntimeException}（编排层据此降级为纯字幕）；返回音频为
 * PCM 24kHz 16bit 单声道（DashScope 输出格式，前端播前自行封装 WAV 头）。
 * 何时升级为流式端口见 ADR §何时重新评估。
 */
public interface TtsProvider {

    /** 模型 id（用量归属口径）。 */
    String name();

    /** 供应通道标识（dashscope/fake）。 */
    String channel();

    /**
     * 合成一段文本。
     *
     * @param text 待合成文本（UTF-8，句级粒度）
     * @param options 音色等会话级参数
     * @return PCM 音频字节；空白文本返回空数组
     */
    byte[] synthesize(String text, TtsOptions options);
}
