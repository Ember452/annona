package io.annona.modules.planner.trace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
