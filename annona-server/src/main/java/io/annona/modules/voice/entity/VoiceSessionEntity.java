package io.annona.modules.voice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 语音面试会话（V18 voice_session，voice-adr §决策 3）：独立于 interview_session 的
 * 对话式会话。音频不持久化——{@code transcript} 是唯一内容存留物（VAD 定稿按序拼接）。
 *
 * <p>状态机（chk_voice_session_status）：CREATED → ACTIVE → (PAUSED ↔ ACTIVE) →
 * FINALIZED / ABANDONED。全部迁移走 repository 条件 UPDATE（全仓状态机纪律，
 * interview-session-adr §状态机），本实体不承载 find→setStatus→save 通道。
 */
@Entity
@Table(name = "voice_session")
public class VoiceSessionEntity {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_FINALIZED = "FINALIZED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "direction_id")
    private UUID directionId;

    /** CREATED/ACTIVE/PAUSED/FINALIZED/ABANDONED（V18 CHECK）。 */
    @Column(name = "status", nullable = false)
    private String status;

    /** 开场白快照：创建时从 voice 配置落定，之后改配置不影响已开会话。 */
    @Column(name = "opening")
    private String opening;

    /** 本会话 ASR 模型快照（用量归属）；provider=none 时 NULL（降级手动提交）。 */
    @Column(name = "asr_model")
    private String asrModel;

    /** 本会话 TTS 模型快照（用量归属）；provider=none 时 NULL（降级纯字幕）。 */
    @Column(name = "tts_model")
    private String ttsModel;

    /** VAD 定稿转写的顺序聚合；partial 草稿绝不入列（端口契约）。 */
    @Column(name = "transcript", nullable = false)
    private String transcript = "";

    /** 端到端延迟 P50（毫秒）：停止说话→首包音频；P3-06 唯一对外验收数字的落库位。 */
    @Column(name = "e2e_latency_p50_ms")
    private Integer e2eLatencyP50Ms;

    /** 端到端延迟 P95（毫秒），口径同 p50。 */
    @Column(name = "e2e_latency_p95_ms")
    private Integer e2eLatencyP95Ms;

    /**
     * 开场时从题库 activePool 截取的题目 id 队列快照（JSON 数组字符串，保序）；重连后按它
     * 恢复队列，不重查题库。空数组 = 未绑定方向或题库为空（对话退化为自由问答，不进评分）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question_ids", nullable = false, columnDefinition = "jsonb")
    private String questionIds = "[]";

    /** 当前进行到的题目下标（0 基）；条件 UPDATE 随轮推进。 */
    @Column(name = "current_question_seq", nullable = false)
    private int currentQuestionSeq;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

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

    public String getOpening() {
        return opening;
    }

    public void setOpening(String opening) {
        this.opening = opening;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel;
    }

    public String getTtsModel() {
        return ttsModel;
    }

    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public Integer getE2eLatencyP50Ms() {
        return e2eLatencyP50Ms;
    }

    public void setE2eLatencyP50Ms(Integer e2eLatencyP50Ms) {
        this.e2eLatencyP50Ms = e2eLatencyP50Ms;
    }

    public Integer getE2eLatencyP95Ms() {
        return e2eLatencyP95Ms;
    }

    public void setE2eLatencyP95Ms(Integer e2eLatencyP95Ms) {
        this.e2eLatencyP95Ms = e2eLatencyP95Ms;
    }

    public String getQuestionIds() {
        return questionIds;
    }

    public void setQuestionIds(String questionIds) {
        this.questionIds = questionIds;
    }

    public int getCurrentQuestionSeq() {
        return currentQuestionSeq;
    }

    public void setCurrentQuestionSeq(int currentQuestionSeq) {
        this.currentQuestionSeq = currentQuestionSeq;
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

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(Instant finalizedAt) {
        this.finalizedAt = finalizedAt;
    }
}
