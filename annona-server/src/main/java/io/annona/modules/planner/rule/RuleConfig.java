package io.annona.modules.planner.rule;

import io.annona.modules.planner.mastery.MasteryParams;

/**
 * 规则与 guard 共用的参数视图（不可变）。由 {@code PlannerProperties} 在装配时构造，
 * 单测里直接 new——规则因此不依赖 Spring，保持纯逻辑可 golden 复现。
 *
 * @param masteryParams        掌握度模型参数
 * @param minSample            样本量下限（低于此不调难度，§6.4 保护 1）
 * @param baselineSessions     换模型后的基线采集场数（§6.4 保护 4）
 * @param weakScoreThreshold   弱项低分线：方向均分低于此 → WEAK_DIRECTION 加压
 * @param forgettingFloor      遗忘底线：掌握度现值低于此 → FORGETTING_CURVE 掺复习
 * @param reviewRatio          复习槽占比上限（advisor 据此限量取最低分历史题）
 * @param windowDays           学习侧信号回看窗口天数（自习室时长聚合的时间跨度）；
 *                             <b>面试侧样本不受它限制</b>——按条数取最近 10 场，否则
 *                             “久不练”永远被窗口过滤成“数据不足”（planner-adr 修订 1）
 */
public record RuleConfig(
    MasteryParams masteryParams,
    int minSample,
    int baselineSessions,
    double weakScoreThreshold,
    double forgettingFloor,
    double reviewRatio,
    int windowDays) {
}
