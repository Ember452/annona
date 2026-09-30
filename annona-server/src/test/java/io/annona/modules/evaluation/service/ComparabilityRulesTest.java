package io.annona.modules.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.modules.evaluation.service.ComparabilityRules.ScoredAnswer;
import io.annona.modules.evaluation.service.ComparabilityRules.Trace;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 可比性规则的关键算法测试（AGENTS §4：golden 快照 + 行为规格）。加权总分数值钉 golden，
 * 趋势断开是出口④的行为规格（换 evaluator_version 自动断开并给出原因）。
 */
@DisplayName("ComparabilityRules：难度加权 golden 与趋势断开行为规格")
class ComparabilityRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Trace trace(String version) {
        return new Trace(version, "glm-x", "glm-x", "hash-abc");
    }

    @Nested
    @DisplayName("难度加权总分")
    class WeightedTotal {

        @Test
        @DisplayName("golden：跨难度加权、降级题不计入，总分与快照一致")
        void matchesGolden() throws Exception {
            JsonNode golden = mapper.readTree(
                getClass().getResourceAsStream("/golden/comparability/weighted.json"));
            List<ScoredAnswer> answers = new java.util.ArrayList<>();
            for (JsonNode a : golden.get("answers")) {
                answers.add(new ScoredAnswer(a.get("score").asInt(), a.get("difficulty").asInt(),
                    a.get("gradable").asBoolean()));
            }
            assertThat(ComparabilityRules.weightedTotal(answers))
                .isEqualTo(golden.get("expected").asInt());
        }

        @Test
        @DisplayName("权重单调：难度 5 权重是难度 1 的 1.5 倍，越界钳到 [1,5]")
        void weightMonotonicAndClamped() {
            assertThat(ComparabilityRules.weight(1)).isEqualTo(1.0);
            assertThat(ComparabilityRules.weight(5)).isEqualTo(1.5);
            assertThat(ComparabilityRules.weight(9)).isEqualTo(1.5); // 钳上界
            assertThat(ComparabilityRules.weight(0)).isEqualTo(1.0);  // 钳下界
        }

        @Test
        @DisplayName("全降级（不可计算）返回 0，不抛")
        void allDegradedIsZero() {
            assertThat(ComparabilityRules.weightedTotal(
                List.of(ScoredAnswer.notGradable(3), ScoredAnswer.notGradable(5)))).isZero();
        }
    }

    @Nested
    @DisplayName("趋势断开（出口④行为规格）")
    class TrendBreak {

        @Test
        @DisplayName("同版本相邻可比，不断开")
        void sameTraceIsComparable() {
            assertThat(ComparabilityRules.breakReason(trace("v2"), trace("v2"))).isEmpty();
        }

        @Test
        @DisplayName("换 evaluator_version → 断开，原因指名版本变化（趋势图据此断线）")
        void versionChangeBreaksWithReason() {
            var reason = ComparabilityRules.breakReason(trace("v2"), trace("v3"));
            assertThat(reason).isPresent();
            assertThat(reason.get()).contains("v2").contains("v3").contains("评估器版本");
        }

        @Test
        @DisplayName("评分模型变化也断开，且优先归因版本、其次模型")
        void modelChangeBreaks() {
            Trace a = new Trace("v2", "glm-x", "glm-x", "h");
            Trace b = new Trace("v2", "gpt-y", "glm-x", "h");
            var reason = ComparabilityRules.breakReason(a, b);
            assertThat(reason).isPresent();
            assertThat(reason.get()).contains("评分模型");
        }

        @Test
        @DisplayName("留痕序列 v2,v2,v3 切成 [0..1][2..2] 两段（段间断开）")
        void segmentsSplitAtBreak() {
            List<Trace> traces = List.of(trace("v2"), trace("v2"), trace("v3"));
            assertThat(ComparabilityRules.comparableSegmentEnds(traces)).containsExactly(1, 2);
        }

        @Test
        @DisplayName("全可比序列只有一段（末尾下标）")
        void singleSegmentWhenAllComparable() {
            List<Trace> traces = List.of(trace("v2"), trace("v2"), trace("v2"));
            assertThat(ComparabilityRules.comparableSegmentEnds(traces)).containsExactly(2);
        }
    }
}
