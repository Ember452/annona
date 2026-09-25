package io.annona.spi.fake;

import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.InterviewPlan;
import io.annona.spi.planner.DecisionRule;
import java.util.Optional;

/**
 * {@link DecisionRule} 的内存实现。永远返回 {@link Optional#empty()}，即"本规则
 * 不适用"，链上跳过。
 *
 * <p>用途：
 * <ul>
 *   <li>P1c 前 planner 规则链还没实装；interview/orchestrator 想拿一个可注入的
 *       DecisionRule bean 让 advisor 先跑起来，用这个。</li>
 *   <li>验证 "关掉任意一条规则其余仍产出合法计划" 的边界：把真实规则换成
 *       FakeDecisionRule 就是"关掉"那条的等价效果。</li>
 * </ul>
 *
 * <p>子类可以覆写 {@link #apply(DecisionContext, InterviewPlan)} 返回特定的
 * {@link MutableOutcome} 模拟"这条规则命中"的场景，用于测试 planner 的规则链装配。
 */
public class FakeDecisionRule implements DecisionRule {

    private final String key;

    public FakeDecisionRule() {
        this("FAKE");
    }

    public FakeDecisionRule(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public Optional<MutableOutcome> apply(DecisionContext context, InterviewPlan draft) {
        return Optional.empty();
    }
}
