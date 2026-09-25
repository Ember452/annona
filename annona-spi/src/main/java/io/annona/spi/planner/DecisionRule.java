package io.annona.spi.planner;

import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DecisionTrace;
import io.annona.spi.dto.InterviewPlan;
import java.util.Optional;

/**
 * 训练决策规则扩展点。planner 的规则链由若干本接口实现串成；
 * 每条规则独立开关（{@code annona.planner.rule.<key>.enabled}），关掉任意一条其余仍能产出合法计划。
 *
 * <p>已定的五条规则（P1c 落地）：
 * {@code FORGETTING_CURVE / WEAK_DIRECTION / SAMPLE_GUARD / CAP_RATIO / VERSION_BASELINE}。
 * 每条一个实现类，命名 {@code <Key>Rule}。
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
