package io.annona.modules.evaluation.repository;

import io.annona.modules.evaluation.entity.InterviewReportEntity;
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
 * 会话评估报告仓库。状态转移全走条件 UPDATE（fencing，与 interview_session 同一口径）——
 * 影响 0 行 = 状态已被并发/恢复路径改走，调用方据此放弃。报告行由交卷触发器建 PENDING。
 */
public interface InterviewReportRepository extends JpaRepository<InterviewReportEntity, UUID> {

    Optional<InterviewReportEntity> findBySessionIdAndEvaluatorVersion(UUID sessionId,
                                                                        String evaluatorVersion);

    /** 趋势面板：某用户近 N 份 DONE 报告（可比性判定按 evaluator_version 分组断开）。 */
    List<InterviewReportEntity> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId,
                                                                          String status,
                                                                          Pageable pageable);

    /** PENDING→RUNNING，仅当仍 PENDING。返回 1 = 抢到执行权。 */
    @Modifying
    @Query("update InterviewReportEntity r set r.status = 'RUNNING', r.updatedAt = :now"
        + " where r.sessionId = :sessionId and r.evaluatorVersion = :version"
        + " and r.status = 'PENDING'")
    int tryMarkRunning(@Param("sessionId") UUID sessionId, @Param("version") String version,
                       @Param("now") Instant now);

    /** RUNNING→DONE 并落总分/汇总/四留痕；仅当仍 RUNNING（防重复消费覆盖终态）。
     * 原生 SQL：summary 需 {@code CAST(:.. AS jsonb)}（JPQL 不支持 jsonb 转换）。 */
    @Modifying
    @Query(value = "update interview_report set status = 'DONE', composite_score = :compositeScore,"
        + " summary = CAST(:summaryJson AS jsonb), chat_model = :chatModel,"
        + " evaluator_model = :evaluatorModel, prompt_hash = :promptHash, error = null,"
        + " updated_at = :now where session_id = :sessionId and evaluator_version = :version"
        + " and status = 'RUNNING'", nativeQuery = true)
    int markDone(@Param("sessionId") UUID sessionId, @Param("version") String version,
                 @Param("compositeScore") Short compositeScore,
                 @Param("summaryJson") String summaryJson, @Param("chatModel") String chatModel,
                 @Param("evaluatorModel") String evaluatorModel,
                 @Param("promptHash") String promptHash, @Param("now") Instant now);

    /** PENDING/RUNNING→FAILED（重试耗尽/致命失败）；DONE 不可被覆盖。 */
    @Modifying
    @Query("update InterviewReportEntity r set r.status = 'FAILED', r.error = :error,"
        + " r.updatedAt = :now where r.sessionId = :sessionId and r.evaluatorVersion = :version"
        + " and r.status in ('PENDING', 'RUNNING')")
    int markFailed(@Param("sessionId") UUID sessionId, @Param("version") String version,
                   @Param("error") String error, @Param("now") Instant now);

    /** RUNNING→PENDING：消费失败未耗尽重试时回退，等重投递再领（与 questionbank resetForRetry 同构）。 */
    @Modifying
    @Query("update InterviewReportEntity r set r.status = 'PENDING', r.updatedAt = :now"
        + " where r.sessionId = :sessionId and r.evaluatorVersion = :version"
        + " and r.status = 'RUNNING'")
    int markRunningBackToPending(@Param("sessionId") UUID sessionId, @Param("version") String version,
                                 @Param("now") Instant now);

    /** 恢复调度扫描：按状态 + updated_at 找 stale（PENDING 投递丢失 / RUNNING 消费崩溃）。 */
    @Query("select r from InterviewReportEntity r where r.status = :status"
        + " and r.updatedAt < :threshold order by r.updatedAt asc")
    List<InterviewReportEntity> findStale(@Param("status") String status,
                                          @Param("threshold") Instant threshold,
                                          Pageable pageable);
}
