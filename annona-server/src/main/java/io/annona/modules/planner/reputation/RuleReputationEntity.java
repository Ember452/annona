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
 * <p>本实体只作<strong>读模型</strong>：建行与计数都走
 * {@link RuleReputationRepository} 的数据库侧原子语句（计数与阈值停用不能先在 Java 里
 * 算好再写回，那在并发下丢更新，理由见类 Javadoc）。id 仍由应用侧赋值（全仓约定）。
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

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRuleKey() {
        return ruleKey;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
