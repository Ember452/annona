package io.annona.modules.planner.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 掌握度纯函数的边界用例（P1c-02 验收）：首次练习、满分、全错、30 天不练、追问放大、
 * 学习率衰减、置信度、无学习记录中性权重。断言口径用 {@code offset} 容忍浮点尾差。
 */
@DisplayName("MasteryModel 掌握度公式（§6.2 边界）")
class MasteryModelTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static MasteryParams params() {
        return new MasteryParams(21, 0.5, 0.15, 0.35, 0.02, 10, 5, 0.5);
    }

    @Nested
    @DisplayName("单事件")
    class Single {

        @Test
        @DisplayName("首次练习满分：掌握度从 0.5 上升、样本 1、置信度按中性权重")
        void firstFullScoreRaisesMastery() {
            var view = MasteryModel.evaluate(
                List.of(new MasteryEvent(1.0, 0, T0)), null, params(), T0);
            // 手算：gain=1，lr=0.35，mastery'=0.5*0.65+(0.5+0.5)*0.35=0.675
            assertThat(view.mastery()).isCloseTo(0.68, offset(0.005));
            assertThat(view.sampleSize()).isEqualTo(1);
            // confidence = min(1/5,1)*0.5 = 0.1
            assertThat(view.confidence()).isCloseTo(0.1, offset(0.005));
            assertThat(view.lastPracticedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("首次练习零分：掌握度下降但不为负")
        void firstZeroScoreLowersMastery() {
            var view = MasteryModel.evaluate(
                List.of(new MasteryEvent(0.0, 0, T0)), null, params(), T0);
            // gain=clamp(-1)= -1 → mastery'=0.5*0.65+(0.5-0.5)*0.35=0.325
            assertThat(view.mastery()).isCloseTo(0.33, offset(0.005));
            assertThat(view.mastery()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("追问深度放大增益：depth=3 比 depth=0 的掌握度更高")
        void followUpDepthAmplifiesGain() {
            var shallow = MasteryModel.evaluate(
                List.of(new MasteryEvent(1.0, 0, T0)), null, params(), T0);
            var deep = MasteryModel.evaluate(
                List.of(new MasteryEvent(1.0, 3, T0)), null, params(), T0);
            // 满分时 gain 已被 clamp 到 1，但深度因子 (1+0.15*3) 仍放大 → 更深更高
            assertThat(deep.mastery()).isGreaterThan(shallow.mastery());
        }
    }

    @Test
    @DisplayName("无事件：掌握度保持中立先验、样本 0")
    void noEventsKeepsNeutralPrior() {
        var view = MasteryModel.evaluate(List.of(), null, params(), T0);
        assertThat(view.mastery()).isEqualTo(0.5);
        assertThat(view.confidence()).isEqualTo(0.0);
        assertThat(view.sampleSize()).isZero();
        assertThat(view.lastPracticedAt()).isNull();
    }

    @Nested
    @DisplayName("遗忘衰减")
    class Decay {

        @Test
        @DisplayName("30 天不练：掌握度按半衰期衰减（30/21 ≈ 1.43 个半衰期）")
        void longIdleDecays() {
            Instant practice = T0;
            Instant asOf = T0.plus(30, ChronoUnit.DAYS);
            var view = MasteryModel.evaluate(
                List.of(new MasteryEvent(1.0, 0, practice)), null, params(), asOf);
            // 练习后 mastery=0.675，再衰减 30 天：0.675 * 0.5^(30/21) ≈ 0.675*0.371 ≈ 0.25
            assertThat(view.mastery()).isCloseTo(0.25, offset(0.02));
        }

        @Test
        @DisplayName("同日不衰减：asOf==lastPracticedAt 时不因时间下降")
        void sameDayNoExtraDecay() {
            var view = MasteryModel.evaluate(
                List.of(new MasteryEvent(1.0, 0, T0)), null, params(), T0);
            assertThat(view.mastery()).isCloseTo(0.68, offset(0.005));
        }
    }

    @Test
    @DisplayName("学习率随样本增长而衰减（多场稳定后步长变小）")
    void learningRateShrinksWithSamples() {
        // 连续满分场：样本越多 lr 越小，第 2 场的提升幅度应小于第 1 场
        var first = MasteryModel.evaluate(
            List.of(new MasteryEvent(1.0, 0, T0)), 1.0, params(), T0);
        List<MasteryEvent> two = List.of(
            new MasteryEvent(1.0, 0, T0),
            new MasteryEvent(1.0, 0, T0.plus(1, ChronoUnit.DAYS)));
        var second = MasteryModel.evaluate(two, 1.0, params(),
            T0.plus(1, ChronoUnit.DAYS));
        // 第二场虽再满分，但 lr 从 0.35 降到 0.33、且已高，边际提升有限
        assertThat(second.mastery()).isGreaterThan(first.mastery());
        assertThat(second.sampleSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("置信度随样本增长，5 场饱和到 1.0（质量权重=1 时）")
    void confidenceSaturates() {
        List<MasteryEvent> five = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(new MasteryEvent(0.8, 0, T0.plus(i, ChronoUnit.DAYS)));
        }
        var view = MasteryModel.evaluate(five, 1.0, params(),
            T0.plus(4, ChronoUnit.DAYS));
        // min(5/5,1)*1.0 = 1.0
        assertThat(view.confidence()).isCloseTo(1.0, offset(0.005));
    }

    @Test
    @DisplayName("入参越界：weightedScore 非 [0,1] 直接拒绝")
    void rejectsOutOfRangeScore() {
        assertThatThrownBy(() -> new MasteryEvent(1.5, 0, T0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MasteryEvent(0.8, -1, T0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
