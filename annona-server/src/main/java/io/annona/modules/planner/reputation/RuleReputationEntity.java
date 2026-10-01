package io.annona.modules.planner.reputation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 规则声誉行（V16 rule_reputation，P1c-07）：一个 (user, rule_key) 的累计驳回与停用状态。
 * 降权是数据不是代码——规则链据 {@code disabled} 过滤该用户的规则，无需重启即生效。
 *
 * <p>id 应用侧赋值（全仓约定）；updated_at 应用侧推进。
 */
@Entity
@Table(name = "rule_reputation")
public class RuleReputationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "rule_key", nullable = false)
    private String ruleKey;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    @Column(name = "disabled", nullable = false)
    private boolean disabled;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    /** 新建一行（首次驳回某规则）。 */
    public static RuleReputationEntity fresh(UUID userId, String ruleKey, Instant now) {
        RuleReputationEntity e = new RuleReputationEntity();
        e.id = UUID.randomUUID();
        e.userId = userId;
        e.ruleKey = ruleKey;
        e.rejectedCount = 0;
        e.disabled = false;
        e.updatedAt = now;
        return e;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRuleKey() {
        return ruleKey;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public boolean isDisabled() {
        return disabled;
    }

    /** 计一次驳回，达阈值置 disabled（幂等：已停用再计仍停用）。 */
    public void recordRejection(int disableThreshold, Instant now) {
        this.rejectedCount = this.rejectedCount + 1;
        if (this.rejectedCount >= disableThreshold) {
            this.disabled = true;
        }
        this.updatedAt = now;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
