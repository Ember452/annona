package io.annona.common.model;

/**
 * 流式 chat 的回调。回调在 Provider 的流读取线程上触发，实现必须轻——不得做阻塞
 * IO（落库、远端调用）；业务的 SSE 推送与持久化在回调里只做内存操作，或抛给调用方
 * 自己的执行器。
 */
public interface ChatStreamListener {

    /** 增量文本；按调用顺序拼接即完整输出。 */
    void onDelta(String delta);

    /** 流正常结束，{@code fullText} 为全部增量的拼接；之后不会再有回调。 */
    void onComplete(String fullText);

    /** 流失败终止（连接失败 / 上游非 2xx / 中途断流）；此前已收到的增量仍有效，
     * 之后不会再有回调。异常类型不保证是业务异常，错误码映射由调用方负责。 */
    void onError(Throwable cause);
}
