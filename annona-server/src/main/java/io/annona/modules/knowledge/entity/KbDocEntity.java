package io.annona.modules.knowledge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * kb_doc 知识库文档主档（V4 §1；knowledge-ingestion-adr）。
 *
 * <p>不映射正文全文——可由 S3 原件重新解析再生（重建式重嵌的依据）。状态机六态
 * 只允许经 {@link io.annona.modules.knowledge.repository.KbDocRepository} 的条件
 * UPDATE 迁移（attempt_id fencing），禁止无条件 setStatus（ADR §决策 3）。
 *
 * <p>id 由服务层 {@code UUID.randomUUID()} 赋值：save() 走 merge 分支、返回值才是
 * 受管副本（全仓 save/merge 约定，P1a-04 加固批教训）。
 */
@Entity
@Table(name = "kb_doc")
public class KbDocEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_CHUNKING = "CHUNKING";
    public static final String STATUS_EMBEDDING = "EMBEDDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 上传时必选方向（direction ADR）；此处仅存 id 值，归属校验走 DirectionQueryService。 */
    @Column(name = "direction_id", nullable = false)
    private UUID directionId;

    /** 文件字节 SHA-256（十六进制）；(user_id, file_hash) 唯一 = hash 幂等键。 */
    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    /** S3 对象 key；删除级联在事务外删对象，失败可按 key 补偿。 */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /** PENDING | PARSING | CHUNKING | EMBEDDING | READY | FAILED（chk_kb_doc_status）。 */
    @Column(name = "status", nullable = false)
    private String status = STATUS_PENDING;

    /** 向量化进度（SSE 信封的 processed/total 来源）；只在 EMBEDDING 态推进。 */
    @Column(name = "processed_chunks", nullable = false)
    private int processedChunks;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    /** 执行代次 fencing（UUID）；PENDING 时为 NULL。 */
    @Column(name = "attempt_id", length = 36)
    private String attemptId;

    /** 自动恢复次数；达上限转 FAILED，手动 re-vectorize 清零。 */
    @Column(name = "recovery_count", nullable = false)
    private int recoveryCount;

    /** FAILED 原因（面向用户，不存堆栈）。 */
    @Column(name = "error", length = 500)
    private String error;

    /** 分块算法版本（Chunker.VERSION）。 */
    @Column(name = "analyzer_version", nullable = false)
    private String analyzerVersion;

    /** 生成向量所用的 embedding 模型 id；READY 时回写，检索端按它过滤。 */
    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    /** 服务层每次写显式维护（条件 UPDATE 由 @Query 显式 set）。 */
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

    public UUID getDirectionId() {
        return directionId;
    }

    public void setDirectionId(UUID directionId) {
        this.directionId = directionId;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getProcessedChunks() {
        return processedChunks;
    }

    public void setProcessedChunks(int processedChunks) {
        this.processedChunks = processedChunks;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public int getRecoveryCount() {
        return recoveryCount;
    }

    public void setRecoveryCount(int recoveryCount) {
        this.recoveryCount = recoveryCount;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getAnalyzerVersion() {
        return analyzerVersion;
    }

    public void setAnalyzerVersion(String analyzerVersion) {
        this.analyzerVersion = analyzerVersion;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
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
