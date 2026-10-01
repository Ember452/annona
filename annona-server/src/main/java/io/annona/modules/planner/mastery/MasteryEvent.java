package io.annona.modules.planner.mastery;

import java.time.Instant;

/**
 * 一场面试产生的掌握度事件（时间升序喂给 {@link MasteryModel}）。由
 * {@code SessionOutcome} 归一而来：weightedScore = compositeScore/100；降级场
 * （compositeScore=null）不进事件（宁缺勿假分）。
 *
 * @param weightedScore  该场难度加权总分的归一值 [0,1]
 * @param followUpDepth  该场达到的追问层数（增益放大用）
 * @param at             交卷时刻（相邻事件间隔天数的计算基准）
 */
public record MasteryEvent(double weightedScore, int followUpDepth, Instant at) {

    public MasteryEvent {
        if (weightedScore < 0 || weightedScore > 1) {
            throw new IllegalArgumentException("weightedScore 须在 [0,1]，得到 " + weightedScore);
        }
        if (followUpDepth < 0) {
            throw new IllegalArgumentException("followUpDepth 不得为负，得到 " + followUpDepth);
        }
    }
}
