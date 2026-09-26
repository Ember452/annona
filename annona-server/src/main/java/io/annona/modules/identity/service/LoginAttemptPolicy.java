package io.annona.modules.identity.service;

import java.time.Duration;
import java.time.Instant;

/**
 * 登录失败锁定策略（纯函数，时钟可注入以便单测）。阈值 10 次、锁定 15 分钟。
 *
 * <p>与枚举防护配合：无论邮箱是否存在都走同样时长，减少"邮箱是否存在"的侧信道。
 */
public final class LoginAttemptPolicy {

    static final int MAX_FAILS = 10;
    static final Duration LOCK_WINDOW = Duration.ofMinutes(15);
    static final Duration COUNT_TTL = Duration.ofMinutes(15);

    private LoginAttemptPolicy() {
    }

    /** 当前是否处于锁定期：lockedUntil 非空且晚于 now。 */
    public static boolean isLocked(Instant lockedUntil, Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 记一次失败后的新状态：达到阈值则上锁，否则只累加。 */
    public static Outcome recordFailure(int currentFailCount, Instant now) {
        int fails = currentFailCount + 1;
        if (fails >= MAX_FAILS) {
            return new Outcome(fails, now.plus(LOCK_WINDOW));
        }
        return new Outcome(fails, null);
    }

    /**
     * 参与本次累加的“历史计数”：距上次失败超过 {@code COUNT_TTL} 窗口则归零（滑动窗口），
     * 避免旧计数永久累积、过锁后一次错误即重锁。
     */
    public static int effectivePriorCount(int storedCount, Instant lastAt, Instant now) {
        if (lastAt == null) {
            return 0;
        }
        return now.isAfter(lastAt.plus(COUNT_TTL)) ? 0 : storedCount;
    }

    /**
     * @param failCount   失败后的计数
     * @param lockedUntil 锁定期终点（未锁为 null）
     */
    public record Outcome(int failCount, Instant lockedUntil) {
    }
}
