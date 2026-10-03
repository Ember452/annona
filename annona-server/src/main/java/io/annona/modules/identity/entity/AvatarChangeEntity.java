package io.annona.modules.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 头像变更历史（V1 {@code avatar_change}，借 🅢 summer-checkin 的头像历史机制）：
 * 每次换头像把被替换下来的 key 记一行，支撑"回滚到上一张"。
 *
 * <p>id 由应用侧生成而非 (user_id, object_key) 复合主键——V1 注释记录了原因：
 * 回滚到曾用头像会插入同一 object_key 撞主键。
 */
@Entity
@Table(name = "avatar_change")
public class AvatarChangeEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

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

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
