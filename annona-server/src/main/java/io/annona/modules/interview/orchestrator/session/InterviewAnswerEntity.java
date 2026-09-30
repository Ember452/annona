package io.annona.modules.interview.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 逐题/逐追问作答（V9 interview_answer）。建会话时按组卷结果整排落 PENDING 占位
 * （answerText=null），作答是 repository 条件 UPDATE 而非插入——寻址 =
 * {@code (session_id, question_id, follow_up_index)}（uq_answer_slot，interview-session-adr
 * §决策 1：追问不是独立题目行，(question_id, follow_up_index) 即会话内唯一槽位）。
 */
@Entity
@Table(name = "interview_answer")
public class InterviewAnswerEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    /** 0 = 主问题；>=1 为第 n 层追问（skill-questionbank-adr 定位口径）。 */
    @Column(name = "follow_up_index", nullable = false)
    private short followUpIndex;

    @Column(name = "answer_text")
    private String answerText;

    /** PENDING | SUBMITTED（chk_answer_status）。 */
    @Column(name = "answer_status", nullable = false)
    private String answerStatus;

    @Column(name = "submitted_at", columnDefinition = "timestamptz")
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    public static InterviewAnswerEntity placeholder(UUID sessionId, AnswerSlot slot, Instant now) {
        InterviewAnswerEntity a = new InterviewAnswerEntity();
        a.id = UUID.randomUUID();
        a.sessionId = sessionId;
        a.questionId = slot.questionId();
        a.followUpIndex = slot.followUpIndex();
        a.answerStatus = STATUS_PENDING;
        a.updatedAt = now;
        return a;
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

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public String getAnswerStatus() {
        return answerStatus;
    }

    public void setAnswerStatus(String answerStatus) {
        this.answerStatus = answerStatus;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
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
