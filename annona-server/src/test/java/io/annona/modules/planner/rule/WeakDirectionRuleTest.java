package io.annona.modules.planner.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.planner.mastery.MasteryParams;
import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DirectionSignal;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.dto.SignalSnapshot;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WeakDirectionRule 加压与辅助证据")
class WeakDirectionRuleTest {

    private static final String DIR = "00000000-0000-0000-0000-0000000000aa";
    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    private static RuleConfig config() {
        MasteryParams mp = new MasteryParams(21, 0.5, 0.15, 0.35, 0.02, 10, 5, 0.5);
        return new RuleConfig(mp, 3, 3, 60, 0.5, 0.25, 14);
    }

    private static DecisionContext ctx(double avgScore, boolean hasStudy) {
        List<SessionOutcome> sessions = List.of(
            session(1, 55), session(2, 55), session(3, 55));
        DirectionSignal d = new DirectionSignal(DIR, 3, avgScore,
            sessions.get(0).finishedAt(),
            hasStudy ? Duration.ofMinutes(60) : Duration.ZERO, Duration.ZERO);
        SignalSnapshot snap = new SignalSnapshot(USER, AS_OF.minusDays(30), AS_OF,
            Duration.ofMinutes(60), null, 3, List.of(d), sessions);
        return new DecisionContext(USER, AS_OF, snap);
    }

    private static SessionOutcome session(int dayOffset, int score) {
        return new SessionOutcome("s" + dayOffset, DIR, score,
            AS_OF.minus(dayOffset, ChronoUnit.DAYS).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            "chat-m", "eval-m", "hash-stable", "v2");
    }

    @Test
    @DisplayName("有学习记录的低分方向：加压并在 reason 附辅助证据")
    void weakWithStudyAddsAuxEvidence() {
        var outcome = new WeakDirectionRule(config())
            .apply(ctx(55.0, true), PlanDrafts.fromDifficulties(DIR, List.of(2, 2, 2)));
        assertThat(outcome).isPresent();
        assertThat(PlanDrafts.difficultiesOf(outcome.get().plan())).containsExactly(3, 3, 3);
        assertThat(outcome.get().trace().reason()).contains("自习室记录");
    }

    @Test
    @DisplayName("无学习记录的低分方向：仍加压，reason 不含辅助证据（纯面试驱动）")
    void weakWithoutStudyInterviewOnly() {
        var outcome = new WeakDirectionRule(config())
            .apply(ctx(55.0, false), PlanDrafts.fromDifficulties(DIR, List.of(2, 2, 2)));
        assertThat(outcome).isPresent();
        assertThat(outcome.get().trace().reason()).doesNotContain("自习室记录");
    }

    @Test
    @DisplayName("均分达标：不命中，返回 empty")
    void notWeakSkips() {
        Optional<?> outcome = new WeakDirectionRule(config())
            .apply(ctx(85.0, true), PlanDrafts.fromDifficulties(DIR, List.of(3, 3, 3)));
        assertThat(outcome).isEmpty();
    }
}
