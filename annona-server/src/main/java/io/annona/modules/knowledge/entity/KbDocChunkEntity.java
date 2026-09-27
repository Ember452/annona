package io.annona.modules.knowledge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * kb_doc_chunk 检索最小单位（V4 §2）：正文 + 清洗后原文偏移 + 标题路径 + 向量。
 *
 * <p>{@code embedding} 列（pgvector vector(1024)）<b>刻意不映射</b>——JPA 无原生
 * vector 类型，ddl-auto validate 忽略未映射列（direction.parent_id 先例）；向量写入
 * 走 {@link io.annona.modules.knowledge.repository.KbDocChunkRepository} 的原生
 * UPDATE（{@code ::vector} 字面量）。doc 删除经外键 ON DELETE CASCADE 级联清理本表。
 */
@Entity
@Table(name = "kb_doc_chunk")
public class KbDocChunkEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    /** 全文档内连续序号（0..n-1），与 (doc_id) 组成唯一键。 */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    /** 标题路径（"章 > 节 > 小节"）；无标题文档为空串。 */
    @Column(name = "heading_path", nullable = false)
    private String headingPath = "";

    /** 正文在清洗后全文中的起始偏移（含）；P1a-08 引用跳转依据。 */
    @Column(name = "char_start", nullable = false)
    private int charStart;

    @Column(name = "char_end", nullable = false)
    private int charEnd;

    @Column(name = "content", nullable = false)
    private String content;

    /** 分块正文 SHA-256（重分块差分对比用，v1 仅记录）。 */
    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDocId() {
        return docId;
    }

    public void setDocId(UUID docId) {
        this.docId = docId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public void setHeadingPath(String headingPath) {
        this.headingPath = headingPath;
    }

    public int getCharStart() {
        return charStart;
    }

    public void setCharStart(int charStart) {
        this.charStart = charStart;
    }

    public int getCharEnd() {
        return charEnd;
    }

    public void setCharEnd(int charEnd) {
        this.charEnd = charEnd;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
