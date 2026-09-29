package io.annona.modules.interview.orchestrator.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 面试会话仓库。与 {@link InterviewAnswerRepository} 同一约定：状态转移全部是条件 UPDATE
 * （id + 期望状态匹配才生效，返回影响行数），不经 find→setStatus→save——read-modify-write
 * 在多实例与重试并发下会互相覆盖（interview-session-adr §状态机；questionbank 先例）。
 */
public interface InterviewSessionRepository
    extends JpaRepository<InterviewSessionEntity, UUID> {

    /** 归属校验与详情读取的入口；查不到即 2701（不区分"不存在/非本人"，防枚举）。 */
    Optional<InterviewSessionEntity> findByIdAndUserId(UUID id, UUID userId);

    /** 续面恢复列表：只扫在途（partial index idx_session_resumable 的服务端口径）。 */
    List<InterviewSessionEntity> findByUserIdAndDirectionIdAndStatusOrderByCreatedAtDesc(
        UUID userId, UUID directionId, String status);

    /** 面试中心首页：跨方向列全部在途会话。 */
    List<InterviewSessionEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
        UUID userId, String status);

    /**
     * 交卷唯一守门（ADR 决策 6/M8）：RESUMABLE→COMPLETED 且写 evaluator_version + finished_at。
     * affected-rows 是幂等的全部依据——赢者才允许继续写作答终态，败者重读状态给 2702。
     */
    @Modifying
    @Query("update InterviewSessionEntity s set s.status = 'COMPLETED',"
        + " s.evaluatorVersion = :evaluatorVersion, s.finishedAt = :now, s.updatedAt = :now"
        + " where s.id = :id and s.userId = :userId and s.status = 'RESUMABLE'")
    int finalizeIfResumable(@Param("id") UUID id, @Param("userId") UUID userId,
                            @Param("evaluatorVersion") String evaluatorVersion,
                            @Param("now") Instant now);

    /** 手动放弃：仅 RESUMABLE 可弃（已交卷的不可覆盖终态）。 */
    @Modifying
    @Query("update InterviewSessionEntity s set s.status = 'ABANDONED',"
        + " s.finishedAt = :now, s.updatedAt = :now"
        + " where s.id = :id and s.userId = :userId and s.status = 'RESUMABLE'")
    int abandonIfResumable(@Param("id") UUID id, @Param("userId") UUID userId,
                           @Param("now") Instant now);

    /** 新建同方向会话时的自动废弃（ADR 决策 5/M6）：批量 RESUMABLE→ABANDONED，返回被废弃数。 */
    @Modifying
    @Query("update InterviewSessionEntity s set s.status = 'ABANDONED',"
        + " s.finishedAt = :now, s.updatedAt = :now"
        + " where s.userId = :userId and s.directionId = :directionId"
        + " and s.status = 'RESUMABLE'")
    int abandonAllResumable(@Param("userId") UUID userId,
                            @Param("directionId") UUID directionId,
                            @Param("now") Instant now);

    /** 逐题推进恢复位：只前进不回退（current_index < new 才生效），终态会话天然 0 行。 */
    @Modifying
    @Query("update InterviewSessionEntity s set s.currentIndex = :target, s.updatedAt = :now"
        + " where s.id = :id and s.status = 'RESUMABLE' and s.currentIndex < :target")
    int advanceIndexIfResumable(@Param("id") UUID id, @Param("target") short target,
                                @Param("now") Instant now);
}
