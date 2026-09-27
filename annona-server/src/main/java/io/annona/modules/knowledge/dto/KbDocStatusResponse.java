package io.annona.modules.knowledge.dto;

/**
 * 处理状态快照（SSE 进度的轮询兜底数据源，同一形状同一线径）。
 *
 * @param status          状态机六态
 * @param stage           人类可读阶段文案（解析中/分块中/向量化中/就绪/失败）
 * @param processedChunks 已处理分块
 * @param totalChunks     总分块
 * @param message         失败原因或空
 */
public record KbDocStatusResponse(String status, String stage, int processedChunks, int totalChunks, String message) {
}
