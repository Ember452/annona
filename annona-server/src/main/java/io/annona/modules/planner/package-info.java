/**
 * 训练决策层（P1c）：把"凭什么这么考你"落成可解释的规则链——读信号 → 算掌握度 → 跑规则 →
 * 出组卷计划 → 落 trace。是"可解释优先于准确"设计主张的载体。
 *
 * <p>子包：{@code mastery/}（遗忘衰减纯函数）、{@code rule/}（{@code DecisionRule} 实现与规则链）、
 * {@code guard/}（保护规则前置判定）、{@code advisor/}（对外编排入口，interview 唯一同步调用点）、
 * {@code trace/}（decision_trace 落库与只读查询）。
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}、{@code io.annona.shared.*} 只读
 * （信号经 shared.signal.SignalFacade / shared.study / shared.evaluation 端口，题目经
 * shared.question 读模型）。<b>禁止依赖 {@code io.annona.modules.interview/voice/schedule}</b>
 * ——planner 是被调用方（ArchUnit 规则 6），反向依赖会破坏决策的单向数据流。
 * 写路径（trace 落库）只在本模块内，不跨模块写。
 */
package io.annona.modules.planner;
