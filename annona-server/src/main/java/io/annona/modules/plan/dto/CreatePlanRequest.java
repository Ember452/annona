package io.annona.modules.plan.dto;

/**
 * POST /api/plans 请求体。
 *
 * @param document    初始 MD（前端模板库给内容；可为空串）
 * @param directionId 归属方向（可选；联动匹配的缺省来源）
 */
public record CreatePlanRequest(String title, String directionId, String document) {
}
