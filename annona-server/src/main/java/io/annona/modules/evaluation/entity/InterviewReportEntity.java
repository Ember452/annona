package io.annona.modules.evaluation.entity;

import io.annona.modules.evaluation.model.EvaluationSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 会话级评估报告与异步状态（V13 interview_report）。报告页轮询 {@code status}；状态转移
 * 全走 {@code InterviewReportRepository} 的 fencing 条件 UPDATE（与 interview_session 同一
 * 口径，interview-session-adr §状态机），不在实体上 find→set→save。
 *
 * <p>可比性四留痕（{@code chatModel/evaluatorModel/promptHash/evaluatorVersion}）落本行——
 * 它们是一次评估运行的属性（evaluation-pipeline-adr §决策 6），趋势断开判定据此比较连续报告。
 */
@Entity
@Table(name = "interview_report")
public class InterviewReportEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "evaluator_version", nullable = false)
    private String evaluatorVersion;

    /** PENDING | RUNNING | DONE | FAILED（chk_report_status）。 */
    @Column(name = "status", nullable = false)
    private String status;

    /** 难度加权总分 0..100（P1b-07）；未产出（PENDING/RUNNING/FAILED）时 null。 */
    @Column(name = "composite_score")
    private Short compositeScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary")
    private EvaluationSummary summary;

    @Column(name = "chat_model")
    private String chatModel;

    @Column(name = "evaluator_model")
    private String evaluatorModel;

    @Column(name = "prompt_hash")
    private String promptHash;

    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    /** 交卷触发时建的 PENDING 行（其余字段由消费者在 DONE 时补）。 */
    public static InterviewReportEntity pending(UUID sessionId, UUID userId,
                                                String evaluatorVersion, Instant now) {
        InterviewReportEntity r = new InterviewReportEntity();
        r.id = UUID.randomUUID();
        r.sessionId = sessionId;
        r.userId = userId;
        r.evaluatorVersion = evaluatorVersion;
        r.status = STATUS_PENDING;
        r.updatedAt = now;
        return r;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEvaluatorVersion() {
        return evaluatorVersion;
    }

    public String getStatus() {
        return status;
    }

    public Short getCompositeScore() {
        return compositeScore;
    }

    public EvaluationSummary getSummary() {
        return summary;
    }

    public String getChatModel() {
        return chatModel;
    }

    public String getEvaluatorModel() {
        return evaluatorModel;
    }

    public String getPromptHash() {
        return promptHash;
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
