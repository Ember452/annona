package io.annona.modules.plan.dto;

/**
 * POST /api/plans/{id}/studio/chat 请求体（SSE）。
 *
 * @param selection 选中片段（可选，>600 字符由服务端截断为引用条语境）
 */
public record StudioChatRequest(String message, String selection) {
}
