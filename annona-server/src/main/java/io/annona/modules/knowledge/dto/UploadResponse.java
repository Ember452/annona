package io.annona.modules.knowledge.dto;

import java.util.UUID;

/**
 * 上传响应。
 *
 * @param id       文档 id（duplicate=true 时为已有文档 id）
 * @param duplicate 是否命中 hash 幂等（true = 本次零解析/零 token，直接复用已有文档）
 * @param status   文档当前状态（PENDING 或已有文档的实时状态）
 * @param message  面向用户的可读说明
 */
public record UploadResponse(UUID id, boolean duplicate, String status, String message) {
}
