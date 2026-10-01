package io.annona.shared.evaluation;

import io.annona.spi.dto.SessionOutcome;
import java.util.List;
import java.util.UUID;

/**
 * 面试评估结果只读端口（实现见 evaluation 模块的 {@code EvaluationSignalPortImpl}）。
 * shared/signal 门面经本端口拿某方向的最近已完成面试结果（含四留痕），作为掌握度事件的
 * 主输入与 VERSION_BASELINE 的比对源——只读，写需求出现即回 shared ADR 讨论（AGENTS §4）。
 */
public interface EvaluationSignalPort {

    /**
     * 该用户在指定方向下状态为 DONE 的报告里<b>最近 {@code limit} 场</b>的逐场结果（按交卷时间
     * 倒序）。<b>不按日期窗口过滤</b>：遗忘曲线必须看到“久不练”的历史
     * （planner-decision-kernel-adr 修订 1）；只看近期的口径只适用于学习侧时长。实现用原生
     * SQL JOIN interview_session 取 direction_id/finished_at——跨表不跨模块（不 import interview
     * 实体，ArchUnit 只看 Java 依赖）。无 DONE 报告时返回空列表。
     */
    List<SessionOutcome> latestOutcomes(UUID userId, UUID directionId, int limit);

    /**
     * 该方向历史得分最低的题目 ID（时间无关、按得分升序、上限 {@code limit}），复习题选择器的
     * 数据源（P1c-05）。实现用原生 SQL 从 interview_evaluation 取该用户该方向（JOIN interview_session
     * 定位方向）的非降级逐题最低分、且仅保留当前仍 ACTIVE 的题目（JOIN qb_question）——
     * 已归档/删除的题不入选。无历史返空集。
     */
    List<UUID> weakestQuestionIds(UUID userId, UUID directionId, int limit);
}
