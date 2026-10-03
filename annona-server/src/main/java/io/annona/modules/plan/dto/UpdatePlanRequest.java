package io.annona.modules.plan.dto;

/**
 * PATCH /api/plans/{id} 请求体（部分更新；null = 不动）。
 * document 变更会把 source_hash 置空——下次 split 重新计算指纹（ADR §决策 2）。
 */
public record UpdatePlanRequest(String title, String document) {
}
