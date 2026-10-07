package io.annona.common.voice;

/**
 * ASR 转写回调（voice-adr §决策 1）。回调在实现方线程触发，实现体必须轻。
 * 生命周期保证：{@code onReady} 恰好一次；{@code onError} 之后不再有 partial/final；
 * close 后不再有任何回调。
 */
public interface AsrListener {

    /** 上游通道就绪，可以开始 sendAudio。恰好一次。 */
    void onReady();

    /** 中间结果（同句内重复触发，可变草稿）；实时字幕消费，禁止落库。 */
    void onPartial(String text);

    /** VAD 断句定稿文本；上下文与转写落库只消费本回调。 */
    void onFinal(String text);

    /** 通道级错误（连接失败/上游报错/事件解析失败）；触发后本会话不再产出转写。 */
    void onError(Throwable cause);
}
