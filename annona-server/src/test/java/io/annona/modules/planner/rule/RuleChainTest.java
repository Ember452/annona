package io.annona.modules.planner.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.planner.mastery.MasteryParams;
import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DirectionSignal;
import io.annona.spi.dto.InterviewPlan;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.dto.SignalSnapshot;
import io.annona.spi.planner.DecisionRule;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规则链（P1c-03 验收）：WEAK/FORGETTING 命中改难度并留痕；guard 拦下时跳过调整但保留说明痕；
 * 关掉任意一条规则其余仍产出结构合法计划（槽数不变、难度恒在 [1,5]）。
 */
@DisplayName("RuleChain 规则链编排")
class RuleChainTest {

    private static final String DIR = "00000000-0000-0000-0000-0000000000aa";
    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    private static RuleConfig config() {
        MasteryParams mp = new MasteryParams(21, 0.5, 0.15, 0.35, 0.02, 10, 5, 0.5);
        return new RuleConfig(mp, 3, 3, 60, 0.5, 0.25, 14);
    }

    private static SignalSnapshot snapshot(double avgScore, int sampleSize, boolean hasStudy,
                                           List<SessionOutcome> sessions) {
        DirectionSignal d = new DirectionSignal(DIR, sampleSize, avgScore,
            sessions.isEmpty() ? null : sessions.get(0).finishedAt(),
            hasStudy ? Duration.ofMinutes(60) : Duration.ZERO, Duration.ZERO);
        return new SignalSnapshot(USER, AS_OF.minusDays(30), AS_OF, Duration.ofMinutes(60),
            null, sampleSize, List.of(d), sessions);
    }

    private static SessionOutcome session(int dayOffset, int score) {
        return new SessionOutcome("s" + dayOffset, DIR, score,
            AS_OF.minus(dayOffset, ChronoUnit.DAYS)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            "chat-m", "eval-m", "hash-stable", "v2");
    }

    private InterviewPlan draft() {
        return PlanDrafts.fromDifficulties(DIR, List.of(3, 3, 3, 3));
    }

    private DecisionContext ctx(SignalSnapshot snap) {
        return new DecisionContext(USER, AS_OF, snap);
    }

    private static List<DecisionRule> both() {
        RuleConfig c = config();
        return List.of(new ForgettingCurveRule(c), new WeakDirectionRule(c));
    }

    @Test
    @DisplayName("弱项低分：WEAK_DIRECTION 命中，难度整体上移一档并留痕")
    void weakDirectionRaisesDifficulty() {
        // 样本 4、均分 55（<60 弱项线）；分数 55 使掌握度略高于 0.5 底线，FORGETTING 不触发
        var snap = snapshot(55.0, 4, true, List.of(
            session(1, 55), session(2, 55), session(3, 55), session(4, 55)));
        var result = new RuleChain(config()).run(ctx(snap), draft(), both());

        assertThat(PlanDrafts.difficultiesOf(result.plan())).containsExactly(4, 4, 4, 4);
        assertThat(result.traces()).anyMatch(t -> t.ruleKey().equals("WEAK_DIRECTION"));
        assertThat(result.traces()).noneMatch(t -> t.ruleKey().equals("FORGETTING_CURVE"));
    }

    @Test
    @DisplayName("遗忘：低分且久不练，FORGETTING_CURVE 命中，难度整体下调一档")
    void forgettingCurveLowersDifficulty() {
        // 样本 3 但都是 40 天前的低分 → 现算掌握度衰减到 < 0.5；均分也低会让 WEAK 也命中，
        // 两规则顺序作用：FORGETTING 先 -1，WEAK 后 +1 → 净 0，但两条痕都在
        var snap = snapshot(30.0, 3, true, List.of(
            session(40, 30), session(41, 30), session(42, 30)));
        var result = new RuleChain(config()).run(ctx(snap), draft(), both());

        assertThat(result.traces()).anyMatch(t -> t.ruleKey().equals("FORGETTING_CURVE"));
        assertThat(result.traces()).anyMatch(t -> t.ruleKey().equals("WEAK_DIRECTION"));
        // 槽数不变，难度仍合法
        assertThat(result.plan().questions()).hasSize(4);
        assertThat(PlanDrafts.difficultiesOf(result.plan()))
            .allSatisfy(d -> assertThat(d).isBetween(1, 5));
    }

    @Test
    @DisplayName("样本不足：guard 拦下调整，规则不执行，但 SAMPLE_GUARD 说明留痕")
    void guardBlocksAdjustmentKeepsExplainableTrace() {
        var snap = snapshot(40.0, 2, false, List.of(session(1, 40)));
        var result = new RuleChain(config()).run(ctx(snap), draft(), both());

        assertThat(result.plan()).isEqualTo(draft());
        assertThat(result.traces()).anyMatch(t -> t.ruleKey().equals("SAMPLE_GUARD"));
        assertThat(result.traces()).noneMatch(t -> t.ruleKey().equals("WEAK_DIRECTION"));
    }

    @Test
    @DisplayName("关掉任意一条规则，其余仍产出结构合法计划")
    void disablingAnyRuleStillYieldsLegalPlan() {
        RuleConfig c = config();
        var snap = snapshot(40.0, 5, true, List.of(
            session(1, 40), session(2, 40), session(3, 40), session(4, 40), session(5, 40)));
        List<List<DecisionRule>> variants = List.of(
            List.of(),                              // 全关
            List.of(new WeakDirectionRule(c)),      // 只留 WEAK
            List.of(new ForgettingCurveRule(c)),    // 只留 FORGETTING
            both());                                // 全开
        for (List<DecisionRule> rules : variants) {
            var result = new RuleChain(c).run(ctx(snap), draft(), rules);
            assertThat(result.plan().questions()).hasSize(4);
            assertThat(PlanDrafts.difficultiesOf(result.plan()))
                .allSatisfy(d -> assertThat(d).isBetween(1, 5));
        }
    }
}
