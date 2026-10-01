/**
 * 掌握度模型（§6.2）：{@link io.annona.modules.planner.mastery.MasteryModel} 是无 Spring 纯函数，
 * 遗忘衰减 + 练习增益 + 置信度；参数经 {@link MasteryParams} 从 {@code PlannerProperties} 传入。
 *
 * <p>允许依赖：JDK（无 common/spi/shared 依赖，保持可 golden 复现）。AGENTS §4 关键纯逻辑包，
 * 覆盖率 ≥85% + golden 快照。
 */
package io.annona.modules.planner.mastery;
