package io.annona.modules.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 会话审计投影（V1 {@code user_session}）。活跃令牌在 Redis；本表<b>异步/尽力而为</b>写入，
 * 写失败不得影响登录（identity ADR §后果 §2）。
 */
@Entity
@Table(name = "user_session")
public class UserSessionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device")
    private String device;

    @Column(name = "ip")
    private String ip;

    /** 只存 UA 的哈希，不落原始 User-Agent（隐私；借鉴地图「必须改什么」）。 */
    @Column(name = "ua_hash")
    private String uaHash;

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
    private Instant lastSeenAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUaHash() {
        return uaHash;
    }

    public void setUaHash(String uaHash) {
        this.uaHash = uaHash;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
