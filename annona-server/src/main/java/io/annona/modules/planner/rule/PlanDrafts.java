package io.annona.modules.planner.rule;

import io.annona.spi.dto.InterviewPlan;
import io.annona.spi.dto.PlannedQuestion;
import java.util.ArrayList;
import java.util.List;

/**
 * 组卷草案（{@link InterviewPlan}）与难度序列之间的转换与调整工具（纯函数）。
 *
 * <p>planner 的决策粒度是"逐槽难度序列"（对齐 orchestrator 的
 * {@code plan.InterviewPlan(totalCount, difficulties, followUpDepth)}）；SPI 的
 * {@link InterviewPlan} 用 {@link PlannedQuestion} 列表承载，故每个槽用一个
 * {@code questionId=null} 的占位 PlannedQuestion 表示——真实题目由 interview 侧的
 * {@code QuestionPackService} 按难度序列抽取（planner 不选具体题，选复习题的职责在
 * advisor 用复习选择器叠加，见 P1c-05）。规则只改难度，不改槽数。
 */
public final class PlanDrafts {

    /** 难度边界与 orchestrator {@code plan.InterviewPlan} 同口径（[1,5]）；
     * 此处自带常量而非 import interview 包——ArchUnit 规则 6 禁 planner→interview 依赖。 */
    static final int MIN_DIFFICULTY = 1;
    static final int MAX_DIFFICULTY = 5;

    private PlanDrafts() {
    }

    /** 由方向与难度序列构建空槽位草案（每槽一个占位题，reason 留空待规则填）。 */
    public static InterviewPlan fromDifficulties(String directionId, List<Integer> difficulties) {
        List<PlannedQuestion> slots = new ArrayList<>();
        for (int d : difficulties) {
            slots.add(new PlannedQuestion(null, directionId, clamp(d), ""));
        }
        return new InterviewPlan(slots, "初始难度序列");
    }

    /** 取草案的逐槽难度序列。 */
    public static List<Integer> difficultiesOf(InterviewPlan plan) {
        return plan.questions().stream().map(PlannedQuestion::difficulty).toList();
    }

    /**
     * 把整体难度序列按 {@code delta} 平移（逐槽 clamp 到 [1,5]），保留各题 directionId 与槽数。
     * 用于 WEAK_DIRECTION（+1 加压）与 FORGETTING_CURVE（-1 让复习题更易回锅）。
     */
    public static InterviewPlan shift(InterviewPlan plan, int delta) {
        List<PlannedQuestion> slots = new ArrayList<>();
        for (PlannedQuestion q : plan.questions()) {
            slots.add(new PlannedQuestion(q.questionId(), q.directionId(),
                clamp(q.difficulty() + delta), q.reason()));
        }
        return new InterviewPlan(slots, plan.rationale());
    }

    private static int clamp(int difficulty) {
        return Math.max(MIN_DIFFICULTY, Math.min(MAX_DIFFICULTY, difficulty));
    }
}
