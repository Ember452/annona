package io.annona.modules.planner.mastery;

import java.time.Instant;

/**
 * 掌握度评估结果（某用户在某方向、asOf 时刻的掌握状态）。
 *
 * @param mastery          当前掌握度 [0,1]（已含到 asOf 的遗忘衰减）
 * @param confidence       置信度 [0,1]：样本量与数据质量共同决定，供面板显示"数据不足"
 * @param sampleSize       参与计算的有效面试样本数
 * @param lastPracticedAt  最后一次练习时刻；无样本时为 {@code null}
 */
public record MasteryView(double mastery, double confidence, int sampleSize, Instant lastPracticedAt) {
}
