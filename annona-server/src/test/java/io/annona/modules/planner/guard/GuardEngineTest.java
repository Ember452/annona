package io.annona.modules.planner.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.planner.mastery.MasteryParams;
import io.annona.modules.planner.rule.RuleConfig;
import io.annona.modules.planner.rule.SignalFacts;
import io.annona.spi.dto.DecisionTrace;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 保护规则五种场景（P1c-04 验收）：样本不足、全自报、无学习记录=正常、换模型基线、正常放行。
 * 每个拦下/说明都要把原因写进 trace（面板能解释"为什么没动"）。
 */
@DisplayName("GuardEngine 保护规则场景")
class GuardEngineTest {

    private static RuleConfig config() {
        MasteryParams mp = new MasteryParams(21, 0.5, 0.15, 0.35, 0.02, 10, 5, 0.5);
        return new RuleConfig(mp, 3, 3, 60, 0.5, 0.25, 14);
    }

    private static String ruleKeyOf(List<DecisionTrace> notes, String key) {
        return notes.stream().filter(t -> t.ruleKey().equals(key)).findFirst()
            .map(DecisionTrace::ruleKey).orElse(null);
    }

    @Test
    @DisplayName("样本 < minSample：拦下调整并留 SAMPLE_GUARD 痕")
    void insufficientSampleBlocks() {
        var facts = new SignalFacts(2, 70.0, true, false, false);
        var verdict = GuardEngine.evaluate(facts, config());
        assertThat(verdict.allowDifficultyAdjust()).isFalse();
        assertThat(ruleKeyOf(verdict.notes(), "SAMPLE_GUARD")).isNotNull();
    }

    @Test
    @DisplayName("换模型基线期：拦下调整并留 VERSION_BASELINE 痕")
    void baselineBlocks() {
        var facts = new SignalFacts(10, 70.0, true, false, true);
        var verdict = GuardEngine.evaluate(facts, config());
        assertThat(verdict.allowDifficultyAdjust()).isFalse();
        assertThat(ruleKeyOf(verdict.notes(), "VERSION_BASELINE")).isNotNull();
    }

    @Test
    @DisplayName("全 SELF_REPORTED：不拦面试侧调整，但记逐出学习信号说明")
    void selfReportedExcludesLearningButAllowsAdjust() {
        var facts = new SignalFacts(5, 55.0, true, true, false);
        var verdict = GuardEngine.evaluate(facts, config());
        assertThat(verdict.allowDifficultyAdjust()).isTrue();
        assertThat(ruleKeyOf(verdict.notes(), "SELF_REPORTED")).isNotNull();
    }

    @Test
    @DisplayName("无学习记录：正常形态非降级，放行并记 INTERVIEW_ONLY 说明")
    void noStudyRecordIsNormalNotDegradation() {
        var facts = new SignalFacts(5, 70.0, false, false, false);
        var verdict = GuardEngine.evaluate(facts, config());
        assertThat(verdict.allowDifficultyAdjust()).isTrue();
        assertThat(ruleKeyOf(verdict.notes(), "NO_STUDY_RECORD")).isNotNull();
    }

    @Test
    @DisplayName("正常相交样本充分无变更：放行，无保护/说明留痕")
    void healthyAllowsClean() {
        var facts = new SignalFacts(8, 72.0, true, false, false);
        var verdict = GuardEngine.evaluate(facts, config());
        assertThat(verdict.allowDifficultyAdjust()).isTrue();
        assertThat(verdict.notes()).isEmpty();
    }
}
