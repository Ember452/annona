package io.annona.modules.questionbank.repository;

import io.annona.modules.questionbank.entity.QbGenerationTaskEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 出题任务仓库。全部状态转移用条件 UPDATE（id + 期望状态匹配才生效，返回影响行数），
 * 不经 find→setStatus→save：那是 read-modify-write，多实例与重试并发下会互相覆盖
 * （借 🅖 tryMarkProcessing 的原子领取语义；知识库状态机同款约定）。
 */
public interface QbGenerationTaskRepository extends JpaRepository<QbGenerationTaskEntity, UUID> {

    /** 最近一次任务（generation-status 轮询；无历史时 empty）。 */
    Optional<QbGenerationTaskEntity> findTopByUserIdAndDirectionIdOrderByCreatedAtDesc(
        UUID userId, UUID directionId);

    /** 在途任务预检查（友好报错）；并发竞态由 uq_generation_task_inflight 兜底。 */
    boolean existsByUserIdAndDirectionIdAndStatusIn(UUID userId, UUID directionId,
                                                    List<String> statuses);

    /** 原子领取：QUEUED→PROCESSING，仅当 taskId 匹配且仍处 QUEUED（重试/恢复重投后可能已被领取）。 */
    @Modifying
    @Query("update QbGenerationTaskEntity t set t.status = 'PROCESSING', t.updatedAt = :now"
        + " where t.id = :id and t.status = 'QUEUED'")
    int tryMarkProcessing(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * 完成落账：PROCESSING→COMPLETED 并写 saved/skipped 与缺口文案。COMPLETED 是终态——
     * 条件里钉住 PROCESSING，迟到的失败重试无法覆盖既有结果。
     */
    @Modifying
    @Query("update QbGenerationTaskEntity t set t.status = 'COMPLETED', t.savedCount = :saved,"
        + " t.skippedCount = :skipped, t.message = :message, t.updatedAt = :now"
        + " where t.id = :id and t.status = 'PROCESSING'")
    int markCompleted(@Param("id") UUID id, @Param("saved") int saved,
        @Param("skipped") int skipped, @Param("message") String message,
        @Param("now") Instant now);

    /** 失败：QUEUED/PROCESSING 都可标失败（投递失败发生在 QUEUED 期）。 */
    @Modifying
    @Query("update QbGenerationTaskEntity t set t.status = 'FAILED', t.error = :error,"
        + " t.updatedAt = :now where t.id = :id and t.status in ('QUEUED', 'PROCESSING')")
    int markFailed(@Param("id") UUID id, @Param("error") String error, @Param("now") Instant now);

    /** 消费失败且未耗尽重试：PROCESSING→QUEUED（retryCount 记在消息头，随重投递递增）。 */
    @Modifying
    @Query("update QbGenerationTaskEntity t set t.status = 'QUEUED', t.updatedAt = :now"
        + " where t.id = :id and t.status = 'PROCESSING'")
    int resetForRetry(@Param("id") UUID id, @Param("now") Instant now);

    /** 恢复调度扫描：按状态 + updated_at 找 stale（双阈值见 QuestionGenRecoveryScheduler）。 */
    @Query("select t from QbGenerationTaskEntity t where t.status = :status"
        + " and t.updatedAt < :threshold order by t.updatedAt asc")
    List<QbGenerationTaskEntity> findStale(@Param("status") String status,
                                           @Param("threshold") Instant threshold,
                                           org.springframework.data.domain.Pageable pageable);
}
