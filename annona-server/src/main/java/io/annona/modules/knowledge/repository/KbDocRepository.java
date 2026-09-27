package io.annona.modules.knowledge.repository;

import io.annona.modules.knowledge.entity.KbDocEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * kb_doc 仓储：读路径 + 状态机条件 UPDATE 全套（knowledge-ingestion-adr §决策 3，
 * 机制借 🅖 KnowledgeBaseRepository 的条件领取 / 终态保护 / 代次 fencing）。
 *
 * <p><b>全部状态迁移只走本接口的 @Modifying 方法</b>，返回值为受影响行数：
 * 0 = 条件不满足（他人已领取 / 已到终态 / 代次已失效），调用方必须据此放弃或走恢复路径，
 * 禁止用返回值之外的方式（如先查后改）写状态。{@code now} 一律由调用方传入并同步实体，
 * 保证 updated_at 是状态推进的心跳口径。
 */
public interface KbDocRepository extends JpaRepository<KbDocEntity, UUID> {

    /** owner 范围内取文档：查不到即业务错误，不泄露他人文档存在性（direction/study 同口径）。 */
    Optional<KbDocEntity> findByIdAndUserId(UUID id, UUID userId);

    List<KbDocEntity> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    /** hash 幂等键查询：(user_id, file_hash) 唯一（V4 uq_kb_doc_user_hash）。 */
    Optional<KbDocEntity> findByUserIdAndFileHash(UUID userId, String fileHash);

    /** 恢复调度扫描：在途状态里找出最后进展早于阈值的文档。 */
    @Query("select d from KbDocEntity d where d.status in :statuses and d.updatedAt < :threshold")
    List<KbDocEntity> findRecoveryCandidates(@Param("statuses") List<String> statuses,
        @Param("threshold") Instant threshold);

    /** 消费者领取：PENDING → PARSING 并写入新代次；0 = 已被其他实例领取。 */
    @Modifying
    @Query("update KbDocEntity d set d.status = 'PARSING', d.attemptId = :attemptId, d.updatedAt = :now"
        + " where d.id = :id and d.status = 'PENDING'")
    int tryMarkParsing(@Param("id") UUID id, @Param("attemptId") String attemptId, @Param("now") Instant now);

    @Modifying
    @Query("update KbDocEntity d set d.status = 'CHUNKING', d.updatedAt = :now"
        + " where d.id = :id and d.status = 'PARSING' and d.attemptId = :attemptId")
    int tryMarkChunking(@Param("id") UUID id, @Param("attemptId") String attemptId, @Param("now") Instant now);

    /** 进入向量化：同时写 total（分块数确定）。 */
    @Modifying
    @Query("update KbDocEntity d set d.status = 'EMBEDDING', d.totalChunks = :totalChunks, d.updatedAt = :now"
        + " where d.id = :id and d.status = 'CHUNKING' and d.attemptId = :attemptId")
    int tryMarkEmbedding(@Param("id") UUID id, @Param("attemptId") String attemptId,
        @Param("totalChunks") int totalChunks, @Param("now") Instant now);

    /** 心跳（消费侧按 30s 节流调用）：代次不匹配（已被回收）返回 0，调用方须立即放弃执行。 */
    @Modifying
    @Query("update KbDocEntity d set d.updatedAt = :now"
        + " where d.id = :id and d.attemptId = :attemptId"
        + " and d.status in ('PARSING', 'CHUNKING', 'EMBEDDING')")
    int heartbeat(@Param("id") UUID id, @Param("attemptId") String attemptId, @Param("now") Instant now);

    /** 向量化进度推进（节流同心跳）。 */
    @Modifying
    @Query("update KbDocEntity d set d.processedChunks = :processed, d.updatedAt = :now"
        + " where d.id = :id and d.status = 'EMBEDDING' and d.attemptId = :attemptId")
    int markProgress(@Param("id") UUID id, @Param("attemptId") String attemptId,
        @Param("processed") int processed, @Param("now") Instant now);

    /** 终态：READY（回写 chunk 数与 embedding 模型，检索按 embedding_model 过滤）。 */
    @Modifying
    @Query("update KbDocEntity d set d.status = 'READY', d.chunkCount = :chunkCount,"
        + " d.embeddingModel = :embeddingModel, d.error = null, d.updatedAt = :now"
        + " where d.id = :id and d.status = 'EMBEDDING' and d.attemptId = :attemptId")
    int markReady(@Param("id") UUID id, @Param("attemptId") String attemptId,
        @Param("chunkCount") int chunkCount, @Param("embeddingModel") String embeddingModel,
        @Param("now") Instant now);

    /** 终态：FAILED（在途代次失败；error 为面向用户的可读原因）。 */
    @Modifying
    @Query("update KbDocEntity d set d.status = 'FAILED', d.error = :error, d.updatedAt = :now"
        + " where d.id = :id and d.attemptId = :attemptId"
        + " and d.status in ('PARSING', 'CHUNKING', 'EMBEDDING')")
    int markFailed(@Param("id") UUID id, @Param("attemptId") String attemptId,
        @Param("error") String error, @Param("now") Instant now);

    /** 投递失败（Redis Stream 不可达等）：文档留在 PENDING 会被恢复调度兜底，仅投递方判死时用。 */
    @Modifying
    @Query("update KbDocEntity d set d.status = 'FAILED', d.error = :error, d.updatedAt = :now"
        + " where d.id = :id and d.status = 'PENDING'")
    int markFailedIfPending(@Param("id") UUID id, @Param("error") String error, @Param("now") Instant now);

    /** 恢复调度：在途超时回收回 PENDING（代次匹配才回收，旧代次无法重置已被重新领取的任务）。 */
    @Modifying
    @Query("update KbDocEntity d set d.status = 'PENDING', d.attemptId = null, d.updatedAt = :now"
        + " where d.id = :id and d.attemptId = :attemptId"
        + " and d.status in ('PARSING', 'CHUNKING', 'EMBEDDING')")
    int resetStaleToPending(@Param("id") UUID id, @Param("attemptId") String attemptId, @Param("now") Instant now);

    /**
     * 恢复调度：PENDING 超时（投递丢失）原子补投去重——recovery_count +1 且推进 updated_at，
     * 同期多个调度实例只有一个拿到返回 1；达到上限的文档随后用 {@link #markRecoveryExhausted} 判死。
     */
    @Modifying
    @Query("update KbDocEntity d set d.recoveryCount = d.recoveryCount + 1, d.updatedAt = :now"
        + " where d.id = :id and d.status = 'PENDING' and d.updatedAt < :threshold")
    int touchPendingForRecovery(@Param("id") UUID id, @Param("threshold") Instant threshold,
        @Param("now") Instant now);

    @Modifying
    @Query("update KbDocEntity d set d.status = 'FAILED', d.error = :error, d.updatedAt = :now"
        + " where d.id = :id and d.status = 'PENDING' and d.recoveryCount >= :maxRecoveryCount")
    int markRecoveryExhausted(@Param("id") UUID id, @Param("maxRecoveryCount") int maxRecoveryCount,
        @Param("error") String error, @Param("now") Instant now);

    /** 手动 re-vectorize（重建式重嵌，ADR §决策 7）：READY/FAILED → PENDING，恢复计数清零。 */
    @Modifying
    @Query("update KbDocEntity d set d.status = 'PENDING', d.attemptId = null, d.recoveryCount = 0,"
        + " d.error = null, d.updatedAt = :now"
        + " where d.id = :id and d.status in ('READY', 'FAILED')")
    int requeueForRevectorize(@Param("id") UUID id, @Param("now") Instant now);

    /** 删除前锁行（S3 key 读取与级联删除的定位基准）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from KbDocEntity d where d.id = :id")
    Optional<KbDocEntity> findByIdForUpdate(@Param("id") UUID id);
}
