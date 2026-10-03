package io.annona.modules.plan.dto;

/**
 * POST /api/plans/{id}/tasks 请求体：手动追加任务。
 *
 * @param directionId   联动方向（缺省继承计划归属方向；显式传 null = 不参与联动）
 * @param targetMinutes 目标分钟（5..600，缺省 25）
 */
public record CreateTaskRequest(String title, String directionId, Integer targetMinutes) {
}
