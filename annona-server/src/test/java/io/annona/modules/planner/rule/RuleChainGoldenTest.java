package io.annona.modules.planner.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.annona.modules.planner.mastery.MasteryParams;
import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DirectionSignal;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.dto.SignalSnapshot;
import io.annona.spi.planner.DecisionRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规则链 golden：固定一条"低分且久不练"的轨迹，钉死最终难度序列与命中规则键序。
 * 改阈值/公式/规则顺序会让 golden 红——需 {@code -Dgolden.update=true} 显式重生并在评审确认。
 *
 * <p>本快照<strong>不</strong>是"决策改变了组卷"的证据：该轨迹下 FORGETTING 的 -1 与 WEAK 的 +1
 * 正好抵消，期望序列等于基线——它钉的是规则顺序、阈值组合与键序。"净变化非零"由
 * {@code RuleChainTest.weakDirectionRaisesDifficulty}（断言 3,3,3,3 → 4,4,4,4）钉住，
 * 两边各管一件事，不要把这里改成基线相等就当回归通过。
 */
@DisplayName("RuleChain golden 快照")
class RuleChainGoldenTest {

    private static final String DIR = "00000000-0000-0000-0000-0000000000aa";
    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("固定轨迹的难度与规则键与快照一致")
    void matchesGolden() throws Exception {
        Path path = Path.of("src/test/resources/golden/planner/rule-chain.json");
        JsonNode golden = mapper.readTree(path.toFile());

        MasteryParams mp = new MasteryParams(
            golden.get("halfLifeDays").asDouble(), golden.get("gainK").asDouble(),
            golden.get("followUpWeight").asDouble(), golden.get("lrBase").asDouble(),
            golden.get("lrDecay").asDouble(), golden.get("lrSampleCap").asInt(),
            golden.get("confidenceDivisor").asInt(), golden.get("neutralQualityWeight").asDouble());
        RuleConfig config = new RuleConfig(mp, golden.get("minSample").asInt(),
            golden.get("baselineSessions").asInt(), golden.get("weakScoreThreshold").asDouble(),
            golden.get("forgettingFloor").asDouble(), golden.get("reviewRatio").asDouble(),
            golden.path("windowDays").asInt(14));

        // 固定输入：3 场 40~42 天前的 30 分（低分久不练 → FORGETTING 与 WEAK 都命中）
        List<SessionOutcome> sessions = List.of(
            session(40, 30), session(41, 30), session(42, 30));
        DirectionSignal d = new DirectionSignal(DIR, 3, 30.0,
            sessions.get(0).finishedAt(), Duration.ofMinutes(60), Duration.ZERO);
        SignalSnapshot snap = new SignalSnapshot(USER, AS_OF.minusDays(45), AS_OF,
            Duration.ofMinutes(60), null, 3, List.of(d), sessions);
        DecisionContext ctx = new DecisionContext(USER, AS_OF, snap);

        List<Integer> baseline = List.of(3, 3, 3, 3);
        List<DecisionRule> rules = List.of(new ForgettingCurveRule(config),
            new WeakDirectionRule(config));
        var result = new RuleChain(config).run(ctx,
            PlanDrafts.fromDifficulties(DIR, baseline), rules);

        ArrayNode actualDifficulties = mapper.createArrayNode();
        PlanDrafts.difficultiesOf(result.plan()).forEach(actualDifficulties::add);
        ArrayNode actualKeys = mapper.createArrayNode();
        result.traces().forEach(t -> actualKeys.add(t.ruleKey()));

        if (Boolean.getBoolean("golden.update")) {
            ObjectNode root = (ObjectNode) golden;
            root.set("expectedDifficulties", actualDifficulties);
            root.set("expectedRuleKeys", actualKeys);
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root));
        }

        assertThat(actualDifficulties).isEqualTo(golden.get("expectedDifficulties"));
        assertThat(actualKeys).isEqualTo(golden.get("expectedRuleKeys"));
    }

    private static SessionOutcome session(int dayOffset, int score) {
        return new SessionOutcome("s" + dayOffset, DIR, score,
            AS_OF.minus(dayOffset, ChronoUnit.DAYS).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            "chat-m", "eval-m", "hash-stable", "v2");
    }
}
