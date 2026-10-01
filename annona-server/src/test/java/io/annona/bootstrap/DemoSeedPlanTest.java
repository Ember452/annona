package io.annona.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** demo 时间计划的确定性与可复现（P1c-08 seed 逻辑，纯函数本机可验）。 */
@DisplayName("DemoSeedPlan 合成时间计划")
class DemoSeedPlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Test
    @DisplayName("面试日期：场次数正确、按距今递增回推、首场在最近")
    void interviewDatesMonotonic() {
        List<LocalDate> dates = DemoSeedPlan.interviewDates(TODAY, 6, 4);
        assertThat(dates).hasSize(4);
        assertThat(dates.get(0)).isEqualTo(TODAY.minusDays(2));   // 最近一场
        // 后面的场次不早于前一场（回推更远）
        for (int i = 1; i < dates.size(); i++) {
            assertThat(dates.get(i)).isBefore(dates.get(i - 1));
        }
    }

    @Test
    @DisplayName("面试日期确定可复现：同输入两次相等")
    void interviewDatesDeterministic() {
        assertThat(DemoSeedPlan.interviewDates(TODAY, 6, 4))
            .isEqualTo(DemoSeedPlan.interviewDates(TODAY, 6, 4));
    }

    @Test
    @DisplayName("学习日期：每周 3 天，覆盖 weeks*3 条")
    void studyDatesCoverage() {
        assertThat(DemoSeedPlan.studyDates(TODAY, 6)).hasSize(18);
        assertThat(DemoSeedPlan.studyDates(TODAY, 1)).hasSize(3);
    }

    @Test
    @DisplayName("幂等：demo 用户已存在则跳过")
    void idempotentSkip() {
        assertThat(DemoSeedPlan.shouldSkip(true)).isTrue();
        assertThat(DemoSeedPlan.shouldSkip(false)).isFalse();
    }
}
