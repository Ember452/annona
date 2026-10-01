package io.annona.shared.study;

import java.time.Duration;

/**
 * 单方向的学习时长聚合（学习侧信号的最小视图，shared 读模型）。质量分级口径唯一权威 =
 * study-collection-adr §决策 2：{@code VERIFIED}/{@code PARTIAL} 计入有效时长，
 * {@code SELF_REPORTED} 单列（永不进难度调整，只进展示统计）。方向由调用参数定位，本视图不冗余存。
 *
 * @param verifiedMinutes      VERIFIED + PARTIAL 的学习分钟数之和
 * @param selfReportedMinutes  SELF_REPORTED 的学习分钟数之和
 */
public record StudySignal(Duration verifiedMinutes, Duration selfReportedMinutes) {

    /** 该方向窗口内有无学习记录（任一质量 > 0）。 */
    public boolean hasRecord() {
        return !verifiedMinutes.isZero() || !selfReportedMinutes.isZero();
    }

    /** 记录是否全部来自 SELF_REPORTED（有效时长为 0 但自报时长 > 0）。 */
    public boolean onlySelfReported() {
        return verifiedMinutes.isZero() && !selfReportedMinutes.isZero();
    }
}
