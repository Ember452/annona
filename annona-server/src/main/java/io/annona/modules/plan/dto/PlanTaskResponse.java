package io.annona.modules.plan.dto;

import java.time.Instant;

/**
 * 计划任务响应（列表/抽屉/今日待办共用形状）。
 *
 * @param status          PENDING | DONE
 * @param targetMinutes   目标专注分钟
 * @param progressMinutes 打卡联动已累计分钟（仅联动监听器维护，ADR §后果）
 * @param source          AI | MANUAL
 */
public record PlanTaskResponse(
    String id,
    String planId,
    String title,
    String description,
    String category,
    String priority,
    String status,
    int targetMinutes,
    int progressMinutes,
    String source
) {
}
