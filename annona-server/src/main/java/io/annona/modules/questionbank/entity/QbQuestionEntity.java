package io.annona.modules.questionbank.entity;

import io.annona.modules.questionbank.model.QbFollowUp;
import io.annona.modules.questionbank.model.QbSourceRef;
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
 * 题目（V8 qb_question）：主问题与追问同构，追问以 JSONB 列内嵌（批 1 追问只读——
 * 抽题/展示；作答记录用 (question_id, follow_up_index) 定位，独立表推迟见 ADR 否决表）。
 * sources 为无 FK 快照（qa citations 同款），真库往返由 docker-it 证伪。
 * id 由服务层 {@code UUID.randomUUID()} 赋值（全仓约定）；created_at 交 DB DEFAULT。
 */
@Entity
@Table(name = "qb_question")
public class QbQuestionEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** 出题发起人；内置方向被多用户共享，题目池按用户隔离。 */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "direction_id", nullable = false)
    private UUID directionId;

    @Column(name = "question", nullable = false)
    private String question;

    @Column(name = "topic_summary")
    private String topicSummary;

    @Column(name = "reference_answer")
    private String referenceAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_points", nullable = false)
    private List<String> keyPoints = List.of();

    /** 评分标准（10 分制分档描述）。 */
    @Column(name = "scoring_rubric")
    private String scoringRubric;

    /** 难度 1..5（决策层数值口径，chk_question_difficulty 兜底）。 */
    @Column(name = "difficulty", nullable = false)
    private short difficulty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "follow_ups", nullable = false)
    private List<QbFollowUp> followUps = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", nullable = false)
    private List<QbSourceRef> sources = List.of();

    /** DRAFT | ACTIVE | ARCHIVED（chk_question_status）；有作答历史时不物理删，走 ARCHIVED。 */
    @Column(name = "status", nullable = false)
    private String status;

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

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getTopicSummary() {
        return topicSummary;
    }

    public void setTopicSummary(String topicSummary) {
        this.topicSummary = topicSummary;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public List<String> getKeyPoints() {
        return keyPoints;
    }

    public void setKeyPoints(List<String> keyPoints) {
        this.keyPoints = keyPoints;
    }

    public String getScoringRubric() {
        return scoringRubric;
    }

    public void setScoringRubric(String scoringRubric) {
        this.scoringRubric = scoringRubric;
    }

    public short getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(short difficulty) {
        this.difficulty = difficulty;
    }

    public List<QbFollowUp> getFollowUps() {
        return followUps;
    }

    public void setFollowUps(List<QbFollowUp> followUps) {
        this.followUps = followUps;
    }

    public List<QbSourceRef> getSources() {
        return sources;
    }

    public void setSources(List<QbSourceRef> sources) {
        this.sources = sources;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
