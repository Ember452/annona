package io.annona.modules.interview.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 面试会话（V9 interview_session）。DB 是冷真值，Redis 快照仅读加速（interview-session-adr
 * §冷热分层）；状态转移一律走 repository 条件 UPDATE，本实体不提供"改 status 再 save"的通道
 * ——那是 read-modify-write，并发下会互相覆盖（QuestionGenStateService 同款约定）。
 *
 * <p>planJson 存服务端校验后的 {@code InterviewPlan} 序列化结果（String + JSON jdbc 类型，
 * QbGenerationTaskEntity.config 先例）：Service 只需要"存进去、批 3 取出来"，不强类型映射。
 * id 由应用侧 {@code UUID.randomUUID()} 赋值（全仓约定，save 走 merge 分支）。
 */
@Entity
@Table(name = "interview_session")
public class InterviewSessionEntity {

    public static final String STATUS_RESUMABLE = "RESUMABLE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    /** 批 2 写死的评估器版本占位（interview-session-adr §决策 1）；批 3 评分器升版必须换新值。 */
    public static final String EVALUATOR_VERSION_V1 = "v1";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "direction_id", nullable = false)
    private UUID directionId;

    /** RESUMABLE | COMPLETED | ABANDONED（chk_session_status）。 */
    @Column(name = "status", nullable = false)
    private String status;

    /**
     * 组卷执行定稿快照（raw JSON 字符串）。{@code @JdbcTypeCode(SqlTypes.JSON)} 必需：
     * 仅 columnDefinition 不会改变 JDBC 绑定类型，String 会按 varchar 送进 jsonb 列
     * （P1b 批 2 CI 实炸；QbGenerationTaskEntity.config 同款先例）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan", nullable = false)
    private String planJson;

    /** 续面恢复位：主问题序号 0..totalCount（== totalCount 表示全部答完待交卷）。 */
    @Column(name = "current_index", nullable = false)
    private short currentIndex;

    @Column(name = "total_count", nullable = false)
    private short totalCount;

    @Column(name = "evaluator_version")
    private String evaluatorVersion;

    @Column(name = "started_at", nullable = false, columnDefinition = "timestamptz")
    private Instant startedAt;

    @Column(name = "finished_at", columnDefinition = "timestamptz")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
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

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public short getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(short currentIndex) {
        this.currentIndex = currentIndex;
    }

    public short getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(short totalCount) {
        this.totalCount = totalCount;
    }

    public String getEvaluatorVersion() {
        return evaluatorVersion;
    }

    public void setEvaluatorVersion(String evaluatorVersion) {
        this.evaluatorVersion = evaluatorVersion;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
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
