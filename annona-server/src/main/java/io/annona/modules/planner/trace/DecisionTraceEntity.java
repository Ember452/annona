package io.annona.modules.planner.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 决策留痕行（V15 decision_trace，P1c-05）。组卷时规则链/guard 的每条命中或保护落一行，
 * 是可解释面板回答"凭什么这么考你"的数据源。
 *
 * <p>id 应用侧 {@code UUID.randomUUID()} 赋值（全仓约定，无 {@code @GeneratedValue}）；
 * {@code created_at} 是 DB default 列（{@code insertable=false}），落库方要回读须接 save
 * 返回值再 refresh（save/merge 语义，P1a-04 踩坑）——本实体的消费方（面板/写侧）都不回读
 * created_at，故直接映射为只读字段即可。
 */
@Entity
@Table(name = "decision_trace")
public class DecisionTraceEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "direction_id", nullable = false)
    private UUID directionId;

    @Column(name = "rule_key", nullable = false)
    private String ruleKey;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "rejected_by")
    private String rejectedBy;

    @Column(name = "rejection_count", nullable = false)
    private int rejectionCount;

    /** 决策时的信号快照 JSON（可空——降级路径不留快照）；原样存 JSONB 文本。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", columnDefinition = "jsonb")
    private String inputSnapshot;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

    /** 由 SPI {@code DecisionTrace} + 归属组装一行（写侧唯一入口，保证字段一致）。 */
    public static DecisionTraceEntity of(UUID sessionId, UUID userId, UUID directionId,
                                         String ruleKey, String action, String reason,
                                         String rejectedBy, String inputSnapshot) {
        DecisionTraceEntity e = new DecisionTraceEntity();
        e.id = UUID.randomUUID();
        e.sessionId = sessionId;
        e.userId = userId;
        e.directionId = directionId;
        e.ruleKey = ruleKey;
        e.action = action;
        e.reason = reason;
        e.rejectedBy = rejectedBy;
        e.rejectionCount = 0;
        e.inputSnapshot = inputSnapshot;
        return e;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getDirectionId() {
        return directionId;
    }

    public String getRuleKey() {
        return ruleKey;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    /** 是否已被用户驳回（串行重复驳的快路径；并发双驳由 {@code markRejectedIfOpen} 的
     *  条件 UPDATE 兜底，所以下游声誉计数不会被同一条 trace 加两次）。 */
    public boolean isUserRejected() {
        return "USER".equals(rejectedBy);
    }

    public int getRejectionCount() {
        return rejectionCount;
    }

    public String getInputSnapshot() {
        return inputSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
