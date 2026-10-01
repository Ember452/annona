package io.annona.modules.planner.advisor;

import io.annona.spi.dto.DecisionTrace;
import java.util.List;
import java.util.UUID;

/**
 * planner 对 interview 的输出（planner-decision-kernel-adr 决策 4）。interview 侧据此把难度
 * 序列灌进 {@code plan.InterviewPlan} 并把复习题 ID 传给组卷；trace 与会话一起落 decision_trace。
 *
 * @param difficulties       逐槽难度序列（长度 = totalCount，每项 [1,5]）
 * @param reviewQuestionIds  需优先掺入的复习题 ID（可空集；组卷在同难度桶内置顶）
 * @param traces             本次决策的全部留痕（含调整与保护说明）
 * @param inputSnapshotJson  决策依据的信号快照 JSON（可空），供面板核对"凭什么"
 */
public record PlanDecision(
    List<Integer> difficulties,
    List<UUID> reviewQuestionIds,
    List<DecisionTrace> traces,
    String inputSnapshotJson) {

    public PlanDecision {
        difficulties = List.copyOf(difficulties);
        reviewQuestionIds = List.copyOf(reviewQuestionIds);
        traces = List.copyOf(traces);
    }
}
