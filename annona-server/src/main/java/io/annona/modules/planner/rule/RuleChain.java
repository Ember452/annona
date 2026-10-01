package io.annona.modules.planner.rule;

import io.annona.modules.planner.guard.GuardEngine;
import io.annona.modules.planner.guard.GuardVerdict;
import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DecisionTrace;
import io.annona.spi.dto.InterviewPlan;
import io.annona.spi.planner.DecisionRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 规则链编排（P1c-03）：guard 前置 → 顺序应用已启用的调整型规则 → 汇总计划与留痕。
 *
 * <p>核心保证"关掉任意一条，其余仍产出合法计划"：本类不做规则内建，只消费传入的
 * {@link DecisionRule} 列表（调用方按 {@code annona.planner.rule.*.enabled} 与降权状态过滤后传入），
 * 任一规则缺席，链仍从初始草案产出结构合法的 {@link InterviewPlan}。
 *
 * <p>guard 拦下调整时（{@code allowDifficultyAdjust=false}）跳过调整型规则，但 guard 已产出的
 * NO_ADJUST/BASELINE 留痕照常返回——保护也要可解释。规则的"不适用"（返回 empty）与 guard 的
 * "拦截"共用同一事实源 {@link SignalFacts}，口径一致不各判各的。
 */
public final class RuleChain {

    /** 一次规则链运行的产出：定稿草案 + 全部留痕（含 guard 保护留痕）。 */
    public record Result(InterviewPlan plan, List<DecisionTrace> traces) {
        public Result {
            traces = List.copyOf(traces);
        }
    }

    private final RuleConfig config;

    public RuleChain(RuleConfig config) {
        this.config = config;
    }

    /**
     * @param context 决策上下文（信号快照 + 参考日期）
     * @param draft   初始草案（advisor 用请求的基线难度序列经 {@link PlanDrafts#fromDifficulties} 构建）
     * @param rules   已启用的调整型规则（顺序即执行顺序）
     */
    public Result run(DecisionContext context, InterviewPlan draft, List<DecisionRule> rules) {
        SignalFacts facts = SignalFacts.from(context.signal(), config.baselineSessions());
        GuardVerdict guard = GuardEngine.evaluate(facts, config);

        List<DecisionTrace> traces = new ArrayList<>(guard.notes());
        InterviewPlan plan = draft;
        if (guard.allowDifficultyAdjust()) {
            for (DecisionRule rule : rules) {
                Optional<DecisionRule.MutableOutcome> outcome = rule.apply(context, plan);
                if (outcome.isPresent()) {
                    plan = outcome.get().plan();
                    traces.add(outcome.get().trace());
                }
            }
        }
        return new Result(plan, traces);
    }
}
