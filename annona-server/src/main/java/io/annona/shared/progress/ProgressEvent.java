package io.annona.shared.progress;

/**
 * SSE 进度事件信封（knowledge-ingestion-adr §决策 10 定形，托管于 shared/progress——
 * P1b-02 出题进度复用同一信封，信封是跨模块冻结契约：改字段语义必须同步 ADR）。
 *
 * @param status    状态机当前态（knowledge 六态 / questionbank 四态，各模块自解释）
 * @param stage     阶段文案
 * @param processed 已处理数
 * @param total     总数（未知阶段为 0）
 * @param message   附加说明或空
 */
public record ProgressEvent(String status, String stage, int processed, int total, String message) {
}
