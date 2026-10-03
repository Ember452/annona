package io.annona.modules.plan.dto;

/**
 * PATCH /api/plans/{id}/tasks/{taskId} 请求体：手动勾选/取消勾选。
 * status 只接受 PENDING | DONE；进度不由本端点改（ADR §后果）。
 */
public record PatchTaskRequest(String status) {
}
