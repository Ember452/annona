package io.annona.spi.dto;

import java.util.List;
import java.util.Objects;

/**
 * planner 的最终输出：一组带顺序的候选题 + 汇总理由。interview/orchestrator 依此组卷。
 *
 * @param questions 已排序的候选题（决定面试顺序）
 * @param rationale 整场出卷的总理由；面向可解释面板，禁止为 {@code null}
 */
public record InterviewPlan(List<PlannedQuestion> questions, String rationale) {

    /**
     * 紧凑构造器：做防御性拷贝。
     *
     * <p>本类型在对外发布的契约 jar 里，若直接存下调用方传入的 {@code List}，
     * 任何一侧后续修改都会改变“已经定下来的考卷”，而可解释面板展示的正是它。
     * 契约层必须不可变（FakeRetriever 已这么做，DTO 一开始漏了）。
     */
    public InterviewPlan {
        questions = List.copyOf(questions);
        rationale = Objects.requireNonNull(rationale, "rationale 不得为 null");
    }

    public static InterviewPlan empty() {
        return new InterviewPlan(List.of(), "无可用规则命中");
    }
}
