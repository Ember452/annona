package io.annona.modules.planner.rule;

import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DecisionTrace;
import io.annona.spi.dto.InterviewPlan;
import io.annona.spi.planner.DecisionRule;
import java.util.Optional;

/**
 * WEAK_DIRECTION：方向均分低于 {@code weakScoreThreshold}（且覆盖题量已在 sampleSize 里体现）
 * 时，把本次难度整体上调一档，把弱项逼出来。若该方向<b>同时</b>存在自习室记录，把"近窗口专注
 * 占比"作为辅助证据写进 reason（只增强解释强度，不单独触发加权——设计文档 §6.1，禁止跨方向比较）。
 */
public class WeakDirectionRule implements DecisionRule {

    private final RuleConfig config;

    public WeakDirectionRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String key() {
        return "WEAK_DIRECTION";
    }

    @Override
    public Optional<MutableOutcome> apply(DecisionContext context, InterviewPlan draft) {
        SignalFacts facts = SignalFacts.from(context.signal(), config.baselineSessions());
        if (facts.sampleSize() < config.minSample() || facts.baselineOnly()) {
            return Optional.empty();
        }
        Double avg = facts.avgScore();
        if (avg == null || avg >= config.weakScoreThreshold()) {
            return Optional.empty();
        }
        InterviewPlan adjusted = PlanDrafts.shift(draft, 1);
        return Optional.of(new MutableOutcome(adjusted, DecisionTrace.accepted(key(),
            "RAISE_DIFFICULTY", reason(facts, avg))));
    }

    private String reason(SignalFacts facts, double avg) {
        String base = String.format("该方向近 %d 场均分 %.1f 低于弱项线 %.0f，本次上调难度加压",
            facts.sampleSize(), avg, config.weakScoreThreshold());
        if (facts.hasStudyRecord() && !facts.onlySelfReported()) {
            return base + "；该方向存在自习室记录（辅助证据，不单独加权）";
        }
        return base;
    }
}
