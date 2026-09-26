package io.annona.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LoginAttemptPolicy：失败计数与锁定阈值")
class LoginAttemptPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");

    @Test
    @DisplayName("第 9 次失败不锁；第 10 次失败上锁 15 分钟")
    void locksAtTenthFailure() {
        LoginAttemptPolicy.Outcome nine = LoginAttemptPolicy.recordFailure(8, NOW);
        assertThat(nine.failCount()).isEqualTo(9);
        assertThat(nine.lockedUntil()).isNull();

        LoginAttemptPolicy.Outcome ten = LoginAttemptPolicy.recordFailure(9, NOW);
        assertThat(ten.failCount()).isEqualTo(10);
        assertThat(ten.lockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("滑动窗口：距上次失败超 15 分钟则历史计数归零")
    void decaysStaleWindow() {
        assertThat(LoginAttemptPolicy.effectivePriorCount(7, NOW.minus(Duration.ofMinutes(1)), NOW)).isEqualTo(7);
        assertThat(LoginAttemptPolicy.effectivePriorCount(7, NOW.minus(Duration.ofMinutes(16)), NOW)).isZero();
        assertThat(LoginAttemptPolicy.effectivePriorCount(7, null, NOW)).isZero();
    }

    @Nested
    @DisplayName("isLocked")
    class Locked {
        @Test
        @DisplayName("锁定期终点晚于当前 → 锁")
        void lockedBeforeExpiry() {
            assertThat(LoginAttemptPolicy.isLocked(NOW.plusSeconds(60), NOW)).isTrue();
        }

        @Test
        @DisplayName("无锁或已过锁定期 → 不锁")
        void unlocked() {
            assertThat(LoginAttemptPolicy.isLocked(null, NOW)).isFalse();
            assertThat(LoginAttemptPolicy.isLocked(NOW.minusSeconds(1), NOW)).isFalse();
        }
    }
}
