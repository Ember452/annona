package io.annona.modules.questionbank.dto;

/**
 * 题目状态变更请求：DRAFT ↔ ACTIVE（启用/撤回）、任意 → ARCHIVED（唯一"删除"语义之外的
 * 软下架）；ARCHIVED 不复活（用户已明确下架，重新出题走草稿链路）。
 */
public record UpdateQuestionStatusRequest(String status) {
}
