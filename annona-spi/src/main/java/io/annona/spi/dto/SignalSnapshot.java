package io.annona.spi.dto;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 一段时间窗口内的学习信号快照。planner 的唯一输入源，字段设计对齐可解释面板的展示需要。
 *
 * @param userId            用户主键
 * @param from              窗口起始日（含）
 * @param to                窗口结束日（含）
 * @param totalStudy        累计有效学习时长；{@code VERIFIED} 部分才计入
 * @param completionPercent 计划完成率（0–100）；无计划任务时 {@code null}
 * @param sampleSize        有效样本量（会话数），供 {@code planner/guard} 判定是否够做难度调整
 */
public record SignalSnapshot(
    String userId,
    LocalDate from,
    LocalDate to,
    Duration totalStudy,
    Integer completionPercent,
    int sampleSize) {
}
