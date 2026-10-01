package io.annona.modules.planner.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.annona.modules.planner.mastery.MasteryParams;
import io.annona.modules.planner.rule.ForgettingCurveRule;
import io.annona.modules.planner.rule.RuleConfig;
import io.annona.modules.planner.rule.WeakDirectionRule;
import io.annona.modules.planner.reputation.RuleReputationService;
import io.annona.modules.planner.trace.DecisionTraceWriter;
import io.annona.shared.evaluation.EvaluationSignalPort;
import io.annona.spi.dto.DirectionSignal;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.dto.SignalSnapshot;
import io.annona.spi.planner.DecisionRule;
import io.annona.spi.signal.LearningSignalReader;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerAdvisorService 决策编排（P1c-05）")
class PlannerAdvisorServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    @Mock
    private ObjectProvider<EvaluationSignalPort> evalProviders;
    @Mock
    private EvaluationSignalPort evalPort;
    @Mock
    private DecisionTraceWriter traceWriter;
    @Mock
    private RuleReputationService reputationService;

    private static RuleConfig config() {
        MasteryParams mp = new MasteryParams(21, 0.5, 0.15, 0.35, 0.02, 10, 5, 0.5);
        return new RuleConfig(mp, 3, 3, 60, 0.5, 0.25, 14);
    }

    /** 固定快照的 fake reader（返回给定方向快照，忽略窗口参数）。 */
    private static LearningSignalReader readerReturning(SignalSnapshot snapshot) {
        return new LearningSignalReader() {
            @Override
            public SignalSnapshot read(String userId, LocalDate from, LocalDate to) {
                return snapshot;
            }

            @Override
            public SignalSnapshot readDirectional(String userId, UUID directionId,
                                                  LocalDate from, LocalDate to) {
                return snapshot;
            }
        };
    }

    private static SignalSnapshot snapshot(List<SessionOutcome> sessions, double avg, int sample,
                                           boolean hasStudy) {
        DirectionSignal d = new DirectionSignal(DIR.toString(), sample, avg,
            sessions.isEmpty() ? null : sessions.get(0).finishedAt(),
            hasStudy ? Duration.ofMinutes(60) : Duration.ZERO, Duration.ZERO);
        return new SignalSnapshot(USER.toString(), AS_OF.minusDays(45), AS_OF,
            Duration.ofMinutes(60), null, sample, List.of(d), sessions);
    }

    private static SessionOutcome session(int dayOffset, int score) {
        return new SessionOutcome("s" + dayOffset, DIR.toString(), score,
            AS_OF.minus(dayOffset, ChronoUnit.DAYS).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            "chat-m", "eval-m", "hash-stable", "v2");
    }

    @Test
    @DisplayName("遗忘+弱项命中：难度序列被调整、复习题按最低分选出、留痕含两条规则")
    void decisionDrivesDifficultyAndReview() {
        // 3 场 40+ 天前低分 → 掌握度衰减 <0.5（FORGETTING）且均分 30<60（WEAK）
        var snap = snapshot(List.of(session(40, 30), session(41, 30), session(42, 30)),
            30.0, 3, true);
        List<DecisionRule> rules = List.of(new ForgettingCurveRule(config()),
            new WeakDirectionRule(config()));
        when(evalProviders.getIfAvailable()).thenReturn(evalPort);
        when(reputationService.disabledRuleKeys(USER)).thenReturn(Set.of());
        UUID weak = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        when(evalPort.weakestQuestionIds(eq(USER), eq(DIR), anyInt())).thenReturn(List.of(weak));

        var advisor = new PlannerAdvisorService(readerReturning(snap), config(), rules,
            evalProviders, traceWriter, reputationService);
        PlanDecision decision = advisor.advise(USER, DIR, List.of(3, 3, 3, 3), AS_OF);

        assertThat(decision.difficulties()).hasSize(4);
        assertThat(decision.traces()).anyMatch(t -> t.ruleKey().equals("FORGETTING_CURVE"));
        assertThat(decision.reviewQuestionIds()).containsExactly(weak);
        assertThat(decision.inputSnapshotJson()).contains("sampleSize");
    }

    @Test
    @DisplayName("样本不足：guard 拦下，难度原样、不掺复习题，留痕含 SAMPLE_GUARD")
    void insufficientSampleNoAdjust() {
        var snap = snapshot(List.of(session(1, 40)), 40.0, 1, false);
        List<DecisionRule> rules = List.of(new ForgettingCurveRule(config()),
            new WeakDirectionRule(config()));
        when(reputationService.disabledRuleKeys(USER)).thenReturn(Set.of());
        var advisor = new PlannerAdvisorService(readerReturning(snap), config(), rules,
            evalProviders, traceWriter, reputationService);

        PlanDecision decision = advisor.advise(USER, DIR, List.of(3, 3, 3), AS_OF);

        assertThat(decision.difficulties()).containsExactly(3, 3, 3);
        assertThat(decision.reviewQuestionIds()).isEmpty();
        assertThat(decision.traces()).anyMatch(t -> t.ruleKey().equals("SAMPLE_GUARD"));
    }

    @Test
    @DisplayName("声誉停用某规则后：advisor 过滤该规则，后续决策不再包含其命中（P1 出口③）")
    void disabledRuleIsFiltered() {
        // 低分久不练本会同时触发 FORGETTING + WEAK；停掉 WEAK 后只剩 FORGETTING
        var snap = snapshot(List.of(session(40, 30), session(41, 30), session(42, 30)),
            30.0, 3, true);
        List<DecisionRule> rules = List.of(new ForgettingCurveRule(config()),
            new WeakDirectionRule(config()));
        when(reputationService.disabledRuleKeys(USER)).thenReturn(Set.of("WEAK_DIRECTION"));
        when(evalProviders.getIfAvailable()).thenReturn(evalPort);
        when(evalPort.weakestQuestionIds(eq(USER), eq(DIR), anyInt())).thenReturn(List.of());
        var advisor = new PlannerAdvisorService(readerReturning(snap), config(), rules,
            evalProviders, traceWriter, reputationService);

        PlanDecision decision = advisor.advise(USER, DIR, List.of(3, 3, 3, 3), AS_OF);

        assertThat(decision.traces()).noneMatch(t -> t.ruleKey().equals("WEAK_DIRECTION"));
        assertThat(decision.traces()).anyMatch(t -> t.ruleKey().equals("FORGETTING_CURVE"));
    }
}
