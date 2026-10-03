package io.annona.modules.plan.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划详情（工作室打开时的装载形状）。
 *
 * @param document   MD 全文
 * @param stale      文档与最近一次拆分指纹不一致（true = 建议重拆）；从未拆过且无任务时也为 true
 * @param tasks      全部任务（创建序）
 */
public record PlanDetailResponse(
    String id,
    String title,
    String directionId,
    String document,
    boolean stale,
    List<PlanTaskResponse> tasks,
    Instant updatedAt
) {
}
