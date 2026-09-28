package io.annona.modules.qa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * qa_session 会话（V6 §1；qa-streaming-adr）。与 user/study/interview 的 session 不同构，
 * 不共享不变量（表注释口径）。
 *
 * <p>id 由服务层 {@code UUID.randomUUID()} 赋值：save() 走 merge 分支、返回值才是受管副本
 * （全仓 save/merge 约定，P1a-04 加固批教训）。
 */
@Entity
@Table(name = "qa_session")
public class QaSessionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 首问标题：取问题前 20 字（🅢 回退口径，一期不调 LLM 起标题）。 */
    @Column(name = "title", nullable = false, length = 64)
    private String title;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** 新消息落库时由应用侧推进；会话列表按它倒序。 */
    @Column(name = "updated_at", nullable = false)
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
