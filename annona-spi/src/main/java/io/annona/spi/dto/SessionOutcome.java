package io.annona.spi.dto;

import java.time.Instant;

/**
 * 单场已完成面试的结果摘要（修订 4 新增）。掌握度公式的事件序列输入，也是
 * VERSION_BASELINE 判定"换模型/换 prompt 后只采基线"的留痕比对源——四留痕取自
 * {@code interview_report}（P1b-07 可比性链路的既有列），不另造口径。
 *
 * @param sessionId        会话主键
 * @param directionId      方向主键（信号按方向隔离的分组键）
 * @param compositeScore   难度加权总分 0–100；评估降级（全 fallback）时为 {@code null}，
 *                         该场不进掌握度事件（宁缺勿假分，与 V13 score 列同口径）
 * @param finishedAt       交卷时刻（间隔天数 t 的计算基准）
 * @param chatModel        出题模型（四留痕之一）；P1b 落库可空，null = 留痕缺失，
 *                         VERSION_BASELINE 遇 null 不判"换模型"（无法比对时保持基线连续性）
 * @param evaluatorModel   评分模型（四留痕之一），口径同上
 * @param promptHash       评估提示模板哈希（四留痕之一），口径同上
 * @param evaluatorVersion 评分器版本（可比区间的决定项之一）
 */
public record SessionOutcome(
    String sessionId,
    String directionId,
    Integer compositeScore,
    Instant finishedAt,
    String chatModel,
    String evaluatorModel,
    String promptHash,
    String evaluatorVersion) {
}
