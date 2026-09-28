package io.annona.modules.qa.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话列表项（{@code GET /api/qa/sessions}，按最近活跃倒序）。
 *
 * @param id        会话 id
 * @param title     首问标题
 * @param createdAt 建会话时间
 * @param updatedAt 最近活跃时间（新消息推进）
 */
public record QaSessionResponse(UUID id, String title, Instant createdAt, Instant updatedAt) {
}
