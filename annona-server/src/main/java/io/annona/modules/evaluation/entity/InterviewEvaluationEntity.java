package io.annona.modules.evaluation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 逐题/逐追问评估明细（V13 interview_evaluation）。幂等键
 * {@code (session_id, question_id, follow_up_index, evaluator_version)} = 与 V9
 * uq_answer_slot 同构的重投兜底（interview-session-adr / evaluation-pipeline-adr §幂等）；
 * 写入走 {@code InterviewEvaluationRepository} 的 upsert（原生 INSERT..ON CONFLICT），
 * 本实体不经 save/merge（预置主键 merge 语义陷阱，AGENTS §4）。
 */
@Entity
@Table(name = "interview_evaluation")
public class InterviewEvaluationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    /** 0 = 主问题；>=1 为第 n 层追问。 */
    @Column(name = "follow_up_index", nullable = false)
    private short followUpIndex;

    @Column(name = "evaluator_version", nullable = false)
    private String evaluatorVersion;

    /** 0..100；fallback（降级）时为 null（宁缺勿假分）。 */
    @Column(name = "score")
    private Short score;

    @Column(name = "feedback")
    private String feedback;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strengths", nullable = false)
    private List<String> strengths = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "improvements", nullable = false)
    private List<String> improvements = List.of();

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    /** 降级时保留的模型原始输出；正常路径为 null（出口③审计）。 */
    @Column(name = "raw_response")
    private String rawResponse;

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

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public void setQuestionId(UUID questionId) {
        this.questionId = questionId;
    }

    public short getFollowUpIndex() {
        return followUpIndex;
    }

    public void setFollowUpIndex(short followUpIndex) {
        this.followUpIndex = followUpIndex;
    }

    public String getEvaluatorVersion() {
        return evaluatorVersion;
    }

    public void setEvaluatorVersion(String evaluatorVersion) {
        this.evaluatorVersion = evaluatorVersion;
    }

    public Short getScore() {
        return score;
    }

    public void setScore(Short score) {
        this.score = score;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths == null ? List.of() : strengths;
    }

    public List<String> getImprovements() {
        return improvements;
    }

    public void setImprovements(List<String> improvements) {
        this.improvements = improvements == null ? List.of() : improvements;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public void setFallbackUsed(boolean fallbackUsed) {
        this.fallbackUsed = fallbackUsed;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
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
