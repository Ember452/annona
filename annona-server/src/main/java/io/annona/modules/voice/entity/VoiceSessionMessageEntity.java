package io.annona.modules.voice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 语音面试对话轮（V19 voice_message）：QUESTION（面试官发言：LLM 过渡 + 题干）与
 * ANSWER（候选人本轮作答）交替，seq 会话内保序（uq_voice_message_seq）。
 *
 * <p>session 子表不冗余 user_id（study_event 同款口径：只能经 session_id 访问，user 经
 * voice_session 外键可达且 ON DELETE CASCADE 同级联）。ANSWER 轮关联
 * {@code question_id}，评估据此对齐 {@code gradingByIds} 评分口径（P3-05 可比性）。
 */
@Entity
@Table(name = "voice_message")
public class VoiceSessionMessageEntity {

    public static final String ROLE_QUESTION = "QUESTION";
    public static final String ROLE_ANSWER = "ANSWER";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** 会话内顺序（0 基，QUESTION/ANSWER 交替递增）。 */
    @Column(name = "seq", nullable = false)
    private int seq;

    /** QUESTION | ANSWER（V19 CHECK）。 */
    @Column(name = "role", nullable = false)
    private String role;

    /** ANSWER 轮关联的题库题目；QUESTION 轮可空（过渡语/结束语）。 */
    @Column(name = "question_id")
    private UUID questionId;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

    public static VoiceSessionMessageEntity question(UUID sessionId, int seq, UUID questionId,
                                                     String content) {
        return of(sessionId, seq, ROLE_QUESTION, questionId, content);
    }

    public static VoiceSessionMessageEntity answer(UUID sessionId, int seq, UUID questionId,
                                                   String content) {
        return of(sessionId, seq, ROLE_ANSWER, questionId, content);
    }

    private static VoiceSessionMessageEntity of(UUID sessionId, int seq, String role,
                                                UUID questionId, String content) {
        VoiceSessionMessageEntity m = new VoiceSessionMessageEntity();
        m.id = UUID.randomUUID();
        m.sessionId = sessionId;
        m.seq = seq;
        m.role = role;
        m.questionId = questionId;
        m.content = content;
        return m;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public int getSeq() {
        return seq;
    }

    public String getRole() {
        return role;
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
