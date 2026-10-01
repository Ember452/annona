package io.annona.modules.planner.dto;

import io.annona.modules.planner.trace.DecisionTraceEntity;

/**
 * 决策留痕响应体（面板展示，Controller 出口）。由实体映射，不把 Entity 直接返前端（AGENTS §4）。
 *
 * @param traceId     留痕行主键（驳回按它定位）
 * @param sessionId   所属会话
 * @param directionId 方向
 * @param ruleKey     规则标识
 * @param action      动作
 * @param reason      面向用户的原因文案
 * @param rejectedBy  被否决者（或 USER 表示用户已驳回）；未否决 null
 * @param createdAt   落库时刻 ISO-8601
 */
public record DecisionTraceResponse(
    String traceId,
    String sessionId,
    String directionId,
    String ruleKey,
    String action,
    String reason,
    String rejectedBy,
    String createdAt) {

    public static DecisionTraceResponse from(DecisionTraceEntity e) {
        return new DecisionTraceResponse(e.getId().toString(), e.getSessionId().toString(),
            e.getDirectionId().toString(), e.getRuleKey(), e.getAction(), e.getReason(),
            e.getRejectedBy(), e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }
}
