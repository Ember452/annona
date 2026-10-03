package io.annona.modules.plan.dto;

import java.util.List;

/**
 * POST /api/plans/{id}/split 响应。
 *
 * @param applied false = 文档指纹未变短路返回（未调模型未花 token）
 */
public record SplitResponse(List<PlanTaskResponse> tasks, boolean applied) {
}
