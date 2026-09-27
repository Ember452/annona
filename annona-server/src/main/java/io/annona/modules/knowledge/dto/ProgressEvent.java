package io.annona.modules.knowledge.dto;

/**
 * SSE 进度事件信封（knowledge-ingestion-adr §决策 10 的跨阶段契约：
 * P1b-02 出题进度复用同一信封，改字段语义必须同步 ADR）。
 *
 * @param status  状态机六态
 * @param stage   阶段文案
 * @param processed 已处理数
 * @param total     总数（未知阶段为 0）
 * @param message   附加说明或空
 */
public record ProgressEvent(String status, String stage, int processed, int total, String message) {
}
