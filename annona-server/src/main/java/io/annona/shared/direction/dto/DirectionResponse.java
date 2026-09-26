package io.annona.shared.direction.dto;

import java.time.Instant;

/**
 * 方向字典条目（下拉与列表共用形状；P1a-04 采集侧选择器直接消费，契约不再改）。
 *
 * @param id      direction.id（业务表外键目标）
 * @param key     owner 内唯一的标识（如 java-concurrency / custom-1a2b3c4d）
 * @param name    展示名
 * @param origin  SKILL_BUILTIN | KNOWLEDGE_BASE | USER_CUSTOM | JD_PARSED
 * @param kbDocId origin=KNOWLEDGE_BASE 时的知识库文档 id，其余为 null
 * @param status  ACTIVE | ARCHIVED
 * @param createdAt 落库时间
 */
public record DirectionResponse(
    String id,
    String key,
    String name,
    String origin,
    String kbDocId,
    String status,
    Instant createdAt
) {
}
