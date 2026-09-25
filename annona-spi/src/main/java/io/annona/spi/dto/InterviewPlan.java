package io.annona.spi.dto;

import java.util.List;

/**
 * planner 的最终输出：一组带顺序的候选题 + 汇总理由。interview/orchestrator 依此组卷。
 *
 * @param questions 已排序的候选题（决定面试顺序）
 * @param rationale 整场出卷的总理由；面向可解释面板，禁止为 {@code null}
 */
public record InterviewPlan(List<PlannedQuestion> questions, String rationale) {

    public static InterviewPlan empty() {
        return new InterviewPlan(List.of(), "无可用规则命中");
    }
}
