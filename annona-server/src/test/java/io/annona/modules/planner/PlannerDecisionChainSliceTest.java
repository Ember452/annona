package io.annona.modules.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import io.annona.common.support.AppZones;
import io.annona.config.properties.PlannerProperties;
import io.annona.modules.planner.advisor.PlanDecision;
import io.annona.modules.planner.advisor.PlannerAdvisorService;
import io.annona.modules.planner.reputation.RuleReputationService;
import io.annona.modules.planner.rule.ForgettingCurveRule;
import io.annona.modules.planner.rule.RuleConfig;
import io.annona.modules.planner.rule.WeakDirectionRule;
import io.annona.modules.planner.trace.DecisionTraceWriter;
import io.annona.shared.evaluation.EvaluationSignalPort;
import io.annona.shared.signal.SignalFacade;
import io.annona.shared.study.StudySignal;
import io.annona.shared.study.StudySignalPort;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.planner.DecisionRule;
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

/**
 * 决策链<b>端到端切片</b>（无数据库）：真实的 {@link SignalFacade} + 真实规则链 + 真实
 * {@link RuleConfig} 取值（与 application.yaml 默认值同源）串成一次 {@code advise}。
 *
 * <p>为什么单独有这一条：P1c 的既有测试要么只喂规则（跳过门面），要么只喂门面（跳过规则链），
 * 中间没有接缝测试——真库 IT 一旦红了，无法在本机区分“取数/装配坏了”与“决策逻辑坏了”，
 * 只能靠 CI 日志猜（本轮 reviewQuestionReachesThePaper 等四条 IT 就是栽在这上面）。
 * 本类先把“决策逻辑与装配本身没问题”钉死，剩下的红就只有数据库侧一种解释。
 *
 * <p>只 mock 两个 shared 端口（它们的真实现要数据库）；{@code @Mock} 的是端口，不是被验证的逻辑。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("决策链端到端切片：门面 + 规则链 + advisor（P1c-01/02/03/05 接缝）")
class PlannerDecisionChainSliceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 2);

    @Mock private ObjectProvider<StudySignalPort> studyProviders;
    @Mock private ObjectProvider<EvaluationSignalPort> evalProviders;
    @Mock private StudySignalPort studyPort;
    @Mock private EvaluationSignalPort evalPort;
    @Mock private DecisionTraceWriter traceWriter;
    @Mock private RuleReputationService reputationService;

    /** 与 application.yaml 的 annona.planner.* 默认值一致（改配置默认值时这里要同步）。 */
    private static RuleConfig productionDefaults() {
        PlannerProperties p = new PlannerProperties();
        p.setHalfLifeDays(21);
        p.setGainK(0.5);
        p.setFollowUpWeight(0.15);
        p.setLrBase(0.35);
        p.setLrDecay(0.02);
        p.setLrSampleCap(10);
        p.setConfidenceDivisor(5);
        p.setNeutralQualityWeight(0.5);
        p.setReviewRatio(0.25);
        p.setMinSample(3);
        p.setBaselineSessions(3);
        p.setWindowDays(14);
        p.setRejectDisableCount(3);
        p.setWeakScoreThreshold(60);
        p.setForgettingFloor(0.5);
        return new RuleConfig(p.toMasteryParams(), p.getMinSample(), p.getBaselineSessions(),
            p.getWeakScoreThreshold(), p.getForgettingFloor(), p.getReviewRatio(),
            p.getWindowDays());
    }

    private PlannerAdvisorService chain(List<SessionOutcome> outcomes) {
        RuleConfig config = productionDefaults();
        when(studyProviders.getIfAvailable()).thenReturn(studyPort);
        when(evalProviders.getIfAvailable()).thenReturn(evalPort);
        when(studyPort.studySignal(any(), any(), any(), any()))
            .thenReturn(new StudySignal(Duration.ZERO, Duration.ZERO));
        when(evalPort.latestOutcomes(any(), any(), anyInt())).thenReturn(outcomes);
        when(reputationService.disabledRuleKeys(USER)).thenReturn(Set.of());
        List<DecisionRule> rules = List.of(new ForgettingCurveRule(config),
            new WeakDirectionRule(config));
        return new PlannerAdvisorService(new SignalFacade(studyProviders, evalProviders),
            config, rules, evalProviders, traceWriter, reputationService);
    }

    @Test
    @DisplayName("脏行：某场 finishedAt 为 null 时，决策链降级为样本不足而不是抛异常")
    void nullFinishedAtDoesNotKillTheChain() {
        // 真库里 finished_at 可以为 NULL（session 未收尾），而报告可以是 DONE；
        // 排序里的 NPE 会被 Facade 吞成“本次由默认策略出题”——整个决策层静默停摆。
        SessionOutcome missingTime = new SessionOutcome(UUID.randomUUID().toString(),
            DIR.toString(), 30, null, "chat-m", "eval-m", "hash-stable", "v2");
        var advisor = chain(List.of(missingTime, outcome(30, 59), outcome(30, 58)));

        PlanDecision decision = advisor.advise(USER, DIR, List.of(3, 3, 3), AS_OF);

        // 无时刻的行不能当掌握度事件（衰减算不出 t），所以有效样本降到 2 → guard 拦下
        assertThat(decision.traces()).anyMatch(t -> "SAMPLE_GUARD".equals(t.ruleKey()));
        assertThat(decision.traces()).noneMatch(t -> "FORGETTING_CURVE".equals(t.ruleKey())
            || "WEAK_DIRECTION".equals(t.ruleKey()));
        assertThat(decision.difficulties()).containsExactly(3, 3, 3);
    }

    private static SessionOutcome outcome(int score, int daysAgo) {
        return new SessionOutcome(UUID.randomUUID().toString(), DIR.toString(), score,
            AS_OF.minus(daysAgo, ChronoUnit.DAYS).atStartOfDay(AppZones.DAILY).toInstant(),
            "chat-m", "eval-m", "hash-stable", "v2");
    }

    @Test
    @DisplayName("久不练 + 低分：门面把 3 场算成有效样本，规则链两条都命中并留痕")
    void staleLowScoreHistoryDrivesBothRules() {
        UUID weak = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        var advisor = chain(List.of(outcome(30, 60), outcome(30, 59), outcome(30, 58)));
        when(evalPort.weakestQuestionIds(any(), any(), anyInt())).thenReturn(List.of(weak));

        PlanDecision decision = advisor.advise(USER, DIR, List.of(3, 3, 3), AS_OF);

        // 样本口径不依赖时间窗口：60 天前的三场必须全都算样本
        assertThat(decision.traces()).extracting(t -> t.ruleKey())
            .contains("WEAK_DIRECTION", "FORGETTING_CURVE", "NO_STUDY_RECORD");
        assertThat(decision.traces()).noneMatch(t -> "SAMPLE_GUARD".equals(t.ruleKey()));
        assertThat(decision.traces()).anyMatch(t -> "REMIND_REVIEW".equals(t.ruleKey()));
        assertThat(decision.reviewQuestionIds()).containsExactly(weak);
        // 两条调整方向相反，净效果为 0 是预期：难度序列合法且槽数不变即可
        assertThat(decision.difficulties()).containsExactly(3, 3, 3);
    }

    @Test
    @DisplayName("高分近期历史：两条调整规则都不命中，只留信息性说明")
    void healthyRecentHistoryAdjustsNothing() {
        var advisor = chain(List.of(outcome(92, 3), outcome(95, 2), outcome(90, 1)));

        PlanDecision decision = advisor.advise(USER, DIR, List.of(3, 3, 3), AS_OF);

        assertThat(decision.traces()).noneMatch(t -> "WEAK_DIRECTION".equals(t.ruleKey())
            || "FORGETTING_CURVE".equals(t.ruleKey()));
        assertThat(decision.difficulties()).containsExactly(3, 3, 3);
    }
}
