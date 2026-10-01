package io.annona.spi.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * 单个方向的信号聚合（修订 4 新增，direction ADR 修订 3 遗留义务）。
 * planner 的"方向相交"判定与掌握度输入全部从这里取，禁止在规则里跨方向比较学习时长
 * （设计文档 §6.1：背政治的时长对 Java 面试毫无意义）。
 *
 * @param directionId           方向主键（direction.id，全仓约定不用 key 字符串）
 * @param sessions              窗口内该方向的已完成面试场数（sampleSize 的口径来源）
 * @param avgScore              该方向逐场总分的均值（0–100）；{@code sessions=0} 时为 {@code null}——
 *                              宁缺勿假值，面板据此显示"数据不足"而非 0 分
 * @param lastPracticedAt       该方向最后一次交卷时刻；无记录时 {@code null}（间隔天数由此推导）
 * @param verifiedStudyMinutes  窗口内该方向 VERIFIED + PARTIAL 的学习分钟数（PARTIAL 按墙钟补回，
 *                              口径唯一权威 = study-collection-adr §决策 2）
 * @param selfReportedMinutes   窗口内该方向 SELF_REPORTED（手动补录/打卡自报）的学习分钟数；
 *                              永不进入难度调整计算，只进展示统计（设计文档 §6.1）
 */
public record DirectionSignal(
    String directionId,
    int sessions,
    Double avgScore,
    Instant lastPracticedAt,
    Duration verifiedStudyMinutes,
    Duration selfReportedMinutes) {

    /** 该方向是否有学习记录（两种质量任一 > 0）；false = 方向不相交，属正常形态而非降级。 */
    public boolean hasStudyRecord() {
        return !verifiedStudyMinutes.isZero() || !selfReportedMinutes.isZero();
    }

    /** 学习记录是否全部来自 SELF_REPORTED——guard 据此把该方向的学习信号逐出决策（保护规则 2）。 */
    public boolean studyOnlySelfReported() {
        return verifiedStudyMinutes.isZero() && !selfReportedMinutes.isZero();
    }
}
