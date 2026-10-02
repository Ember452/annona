package io.annona.modules.planner.trace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 决策留痕仓库（P1c-05）。读路径两条：单场全部留痕（报告页决策理由节）、按用户最近若干场
 * 摘要（首页可解释面板）。写只有 {@code DecisionTraceWriter} 一处（组卷随会话落）。
 */
public interface DecisionTraceRepository extends JpaRepository<DecisionTraceEntity, UUID> {

    /** 单会话全部留痕（带归属校验：session+user 双条件，防跨用户读）；按规则键稳定序。 */
    List<DecisionTraceEntity> findBySessionIdAndUserIdOrderByRuleKeyAsc(UUID sessionId, UUID userId);

    /** 面板"最近 5 场"：按用户时间倒序取（同一会话的多行会相邻出现）。 */
    List<DecisionTraceEntity> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

    /** 反驳定位：按 (id, userId) 取行（不存在/非本人均 Optional.empty，防跨用户驳回）。 */
    Optional<DecisionTraceEntity> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 驳回一条留痕的<b>条件 UPDATE</b>（fencing，与 interview_session / interview_report 同口径）：
     * 仅当该行尚未被驳回时置位并计数。返回 0 = 并发下已被另一路驳回，调用方据此出 3201。
     *
     * <p>为什么不用“先读再判后写”：两个同时到达的驳回请求都能读到未驳回态，各自计数一次，
     * 同一条 trace 会把两次驳回计入规则声誉（阈值 3 虚胖到提前停用）。“一次驳回只计一次”
     * 这条语义必须靠数据库的原子性保证，而不是靠 Java 里的先判。
     */
    @Modifying
    @Query("update DecisionTraceEntity t set t.rejectedBy = 'USER',"
        + " t.rejectionCount = t.rejectionCount + 1"
        + " where t.id = :traceId and t.userId = :userId and t.rejectedBy is null")
    int markRejectedIfOpen(@Param("traceId") UUID traceId, @Param("userId") UUID userId);
}
