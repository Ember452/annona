package io.annona.modules.planner;

import io.annona.config.properties.PlannerProperties;
import io.annona.modules.planner.rule.ForgettingCurveRule;
import io.annona.modules.planner.rule.RuleConfig;
import io.annona.modules.planner.rule.WeakDirectionRule;
import io.annona.spi.planner.DecisionRule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * planner 装配（P1c-03/05）：把 {@link PlannerProperties} 汇成不可变 {@link RuleConfig}，
 * 并把调整型规则注册为 bean——每条一个 {@code @ConditionalOnProperty} 开关
 * （{@code annona.planner.rule.<key>.enabled}，缺省开）。关掉某条 = 该 bean 缺席 =
 * 不进 {@code List<DecisionRule>}，规则链其余仍产出合法计划（P1c-03 验收的机器化）。
 *
 * <p>guard 不是 bean（纯静态判定），其 VERSION_BASELINE/SAMPLE_GUARD 保护不随规则开关变化。
 */
@Configuration
@EnableConfigurationProperties(PlannerProperties.class)
public class PlannerConfig {

    @Bean
    RuleConfig ruleConfig(PlannerProperties p) {
        return new RuleConfig(p.toMasteryParams(), p.getMinSample(), p.getBaselineSessions(),
            p.getWeakScoreThreshold(), p.getForgettingFloor(), p.getReviewRatio(),
            p.getWindowDays());
    }

    @Bean
    @ConditionalOnProperty(prefix = "annona.planner.rule.forgetting-curve", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    DecisionRule forgettingCurveRule(RuleConfig config) {
        return new ForgettingCurveRule(config);
    }

    @Bean
    @ConditionalOnProperty(prefix = "annona.planner.rule.weak-direction", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    DecisionRule weakDirectionRule(RuleConfig config) {
        return new WeakDirectionRule(config);
    }
}
