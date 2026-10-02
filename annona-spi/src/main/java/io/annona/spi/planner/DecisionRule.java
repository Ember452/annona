package io.annona.spi.planner;

import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DecisionTrace;
import io.annona.spi.dto.InterviewPlan;
import java.util.Optional;

/**
 * 训练决策规则扩展点。planner 的规则链由若干本接口实现串成；
 * 每条规则独立开关（{@code annona.planner.rule.<key>.enabled}），关掉任意一条其余仍能产出合法计划。
 *
 * <p>职责边界：本接口只承载<b>调整型</b>规则（命中就改草案）——已落地
 * {@code FORGETTING_CURVE / WEAK_DIRECTION} 两条，每条一个实现类。
 * <b>保护型判定不在这里</b>：样本量下限、换模型基线期、自报逐出、无学习记录四条由
 * {@code modules/planner/guard/GuardEngine} 前置统一判定（拦下时本接口的实现不执行）。
 * 设计文档 §6.4 的“单方向占比上限”（CAP_RATIO）在单方向会话下不成立，已重定位为
 * reviewRatio 治理，<b>无实现</b>（planner-decision-kernel-adr 修订 2）。
 */
public interface DecisionRule {

    /** 规则唯一标识，用于开关注解与 trace 归属。 */
    String key();

    /**
     * 在给定上下文下对草案 {@code draft} 施加本规则。
     * <ul>
     *   <li>不适用（如样本量不足）→ 返回 {@link Optional#empty()}，链上跳过。</li>
     *   <li>命中 → 返回带 {@link DecisionTrace} 的新草案；<b>禁止原地修改 draft</b>。</li>
     * </ul>
     */
    Optional<MutableOutcome> apply(DecisionContext context, InterviewPlan draft);

    /**
     * 规则的一次命中结果：新草案 + 留痕。record 语义但允许 {@code plan} 为原引用的替换版。
     */
    record MutableOutcome(InterviewPlan plan, DecisionTrace trace) { }
}
