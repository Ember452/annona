package io.annona.modules.plan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * plan 学习计划（V17 §1）：MD 文档是真相源，任务由 AI 拆分或手动追加。
 * source_hash 是拆分短路指纹——文档没变就不再花 token（ADR §决策 2）。
 */
@Entity
@Table(name = "plan")
public class PlanEntity {

    /** 标题上限（与列 VARCHAR(120) 对齐）。 */
    public static final int MAX_TITLE_LENGTH = 120;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 归属方向（可选）；任务的联动匹配方向缺省继承本值。 */
    @Column(name = "direction_id")
    private UUID directionId;

    @Column(name = "title", nullable = false)
    private String title;

    /** 计划全文（GFM Markdown）。 */
    @Column(name = "document", nullable = false)
    private String document;

    /** 最近一次拆分时的 sha256(document)；null = 文档已变待重拆。 */
    @Column(name = "source_hash")
    private String sourceHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
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
