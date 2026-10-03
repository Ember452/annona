package io.annona.modules.plan.dto;

import java.time.Instant;

/**
 * 计划列表项（含任务进度摘要，卡片直读免二次请求）。
 *
 * @param totalTasks 任务总数
 * @param doneTasks  已完成任务数（卡片进度 = done/total）
 */
public record PlanSummaryResponse(
    String id,
    String title,
    String directionId,
    long totalTasks,
    long doneTasks,
    Instant updatedAt
) {
}
