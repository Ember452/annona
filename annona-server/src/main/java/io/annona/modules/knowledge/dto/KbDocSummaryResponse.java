package io.annona.modules.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 文档列表项（列表页 + DirectionSelector 绑定选择器的数据源）。
 *
 * @param id              文档 id
 * @param name            展示名（默认去扩展名的原文件名）
 * @param directionId     绑定方向
 * @param fileSize        原始文件字节
 * @param status          状态机六态（KbDocEntity 常量）
 * @param processedChunks 向量化进度（EMBEDDING 态推进）
 * @param totalChunks     分块总数
 * @param chunkCount      READY 后的实际分块数
 * @param error           FAILED 时的可读原因
 * @param createdAt       上传时间
 */
public record KbDocSummaryResponse(UUID id, String name, UUID directionId, long fileSize,
                                   String status, int processedChunks, int totalChunks,
                                   int chunkCount, String error, Instant createdAt) {
}
