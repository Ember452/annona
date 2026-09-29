package io.annona.modules.questionbank.entity;

import io.annona.modules.questionbank.model.QuestionGenConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 出题任务（V8 qb_generation_task）：状态 QUEUED→PROCESSING→COMPLETED/FAILED，重试回
 * QUEUED。id 即 fencing token——Redis Stream 消息与全部状态转移共用，转移一律要求
 * id+status 条件匹配（repository 条件 UPDATE），防多实例重复消费与旧任务覆盖新结果
 * （借 🅖 的原子领取语义）。config 为请求参数快照，消费侧按快照执行。
 */
@Entity
@Table(name = "qb_generation_task")
public class QbGenerationTaskEntity {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "direction_id", nullable = false)
    private UUID directionId;

    @Column(name = "status", nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private QuestionGenConfig config;

    @Column(name = "saved_count", nullable = false)
    private int savedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    /** 完成态缺口提示（"已生成 N 题，跳过 M 道重复"），对外可见。 */
    @Column(name = "message")
    private String message;

    /** 失败原因快照（对对外只暴露安全文案，本列供排障）。 */
    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

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

    public UUID getDirectionId() {
        return directionId;
    }

    public void setDirectionId(UUID directionId) {
        this.directionId = directionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public QuestionGenConfig getConfig() {
        return config;
    }

    public void setConfig(QuestionGenConfig config) {
        this.config = config;
    }

    public int getSavedCount() {
        return savedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public String getMessage() {
        return message;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
