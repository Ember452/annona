package io.annona.common.model;

import io.annona.spi.dto.UsageInfo;

/**
 * 流式 chat 的回调。回调在 Provider 的流读取线程上触发，实现必须轻——不得做阻塞
 * IO（落库、远端调用）；业务的 SSE 推送与持久化在回调里只做内存操作，或抛给调用方
 * 自己的执行器。
 */
public interface ChatStreamListener {

    /** 增量文本；按调用顺序拼接即完整输出。 */
    void onDelta(String delta);

    /**
     * 流正常结束，{@code fullText} 为全部增量的拼接；之后不会再有回调。
     *
     * <p>{@code usage} 为上游终帧返回的 token 用量（OpenAI 兼容的
     * {@code stream_options.include_usage}）；供应商未返回时为全 0，记账侧对
     * total=0 不记行（宁缺毋假账）。<b>调用方不得原地修改拼接缓冲</b>：实现方可能
     * 传入内部 StringBuilder 的视图，长命消费者需自行拷贝。
     */
    void onComplete(String fullText, UsageInfo usage);

    /** 流失败终止（连接失败 / 上游非 2xx / 中途断流）；此前已收到的增量仍有效，
     * 之后不会再有回调。异常类型不保证是业务异常，错误码映射由调用方负责。 */
    void onError(Throwable cause);
}
