package io.annona.modules.planner.rule;

import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DecisionTrace;
import io.annona.spi.dto.InterviewPlan;
import io.annona.spi.planner.DecisionRule;
import java.util.Optional;

/**
 * WEAK_DIRECTION：方向均分低于 {@code weakScoreThreshold}（且有效样本已在 sampleSize 里体现）
 * 时，把本场难度上调一档，把弱项逼出来。
 *
 * <p>学习侧证据的当前形态：该方向存在自习室记录（且非全自报）时，reason 里点明"方向相交"
 * 这一<b>布尔级</b>辅助证据，不参与数值加权（设计文档 §6.1 禁止跨方向比较）。原计划在文案里写
 * "近窗口专注占比"——那需要窗口总时长做分母，DirectionSignal 只提供了分钟数，算不出真实占比，
 * 所以不写假数字（诚实呈现；要上量化辅助证据先扩信号口径，见 planner-adr 修订 2）。
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
        String base = String.format(
            "该方向最近 %d 场有效样本均分 %.1f 低于弱项线 %.0f——本规则将本场难度上调一档",
            facts.sampleSize(), avg, config.weakScoreThreshold());
        if (facts.hasStudyRecord() && !facts.onlySelfReported()) {
            return base + "；该方向存在自习室记录（方向相交，作为辅助证据不参与数值加权）";
        }
        return base;
    }
}
