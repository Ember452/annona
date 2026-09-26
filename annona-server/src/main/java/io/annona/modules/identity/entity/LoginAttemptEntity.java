package io.annona.modules.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 登录失败计数与锁定（V1 {@code login_attempt}）。key = {@code 归一化email|ip} 复合键。
 * 预认证表：失败时还不知道是谁，故无 user_id（identity ADR 已记录的「业务表必含 user_id」例外）。
 */
@Entity
@Table(name = "login_attempt")
public class LoginAttemptEntity {

    @Id
    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "locked_until", columnDefinition = "timestamptz")
    private Instant lockedUntil;

    @Column(name = "last_at", nullable = false, columnDefinition = "timestamptz")
    private Instant lastAt;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getLastAt() {
        return lastAt;
    }

    public void setLastAt(Instant lastAt) {
        this.lastAt = lastAt;
    }
}
