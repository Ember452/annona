package io.annona.modules.resume.repository;

import io.annona.modules.resume.entity.ResumeEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 简历仓库。状态转移全走条件 UPDATE（fencing，复用 knowledge 状态机口径）；
 * {@code markDone} 需写 analysis JSONB 故用原生 SQL（JPQL 不支持 CAST AS jsonb）。
 */
public interface ResumeRepository extends JpaRepository<ResumeEntity, UUID> {

    Optional<ResumeEntity> findByUserIdAndFileHash(UUID userId, String fileHash);

    Optional<ResumeEntity> findByIdAndUserId(UUID id, UUID userId);

    List<ResumeEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** PENDING→PROCESSING，仅当仍 PENDING。返回 1 = 抢到执行权。 */
    @Modifying
    @Query("update ResumeEntity r set r.status = 'PROCESSING', r.updatedAt = :now"
        + " where r.id = :id and r.status = 'PENDING'")
    int tryMarkProcessing(@Param("id") UUID id, @Param("now") Instant now);

    /** PROCESSING→DONE 并落分析结果；仅当仍 PROCESSING。 */
    @Modifying
    @Query(value = "update resume set status = 'DONE', analysis = CAST(:analysisJson AS jsonb),"
        + " error = null, updated_at = :now where id = :id and status = 'PROCESSING'",
        nativeQuery = true)
    int markDone(@Param("id") UUID id, @Param("analysisJson") String analysisJson,
                 @Param("now") Instant now);

    /** PENDING/PROCESSING→FAILED；DONE 不可被覆盖。 */
    @Modifying
    @Query("update ResumeEntity r set r.status = 'FAILED', r.error = :error, r.updatedAt = :now"
        + " where r.id = :id and r.status in ('PENDING', 'PROCESSING')")
    int markFailed(@Param("id") UUID id, @Param("error") String error, @Param("now") Instant now);

    /** PROCESSING→PENDING：消费失败未耗尽重试时回退，等重投递再领（同 questionbank）。 */
    @Modifying
    @Query("update ResumeEntity r set r.status = 'PENDING', r.updatedAt = :now"
        + " where r.id = :id and r.status = 'PROCESSING'")
    int markProcessingBackToPending(@Param("id") UUID id, @Param("now") Instant now);

    /** 恢复调度扫描：按状态 + updated_at 找 stale。 */
    @Query("select r from ResumeEntity r where r.status = :status"
        + " and r.updatedAt < :threshold order by r.updatedAt asc")
    List<ResumeEntity> findStale(@Param("status") String status,
                                 @Param("threshold") Instant threshold, Pageable pageable);
}
