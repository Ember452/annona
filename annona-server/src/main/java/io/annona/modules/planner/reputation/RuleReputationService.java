package io.annona.modules.planner.reputation;

import io.annona.config.properties.PlannerProperties;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则声誉读写（P1c-07）。降权是数据不是代码：规则链装配前查该用户已停用规则（读），
 * 用户点"这条不对"时累计驳回、达阈值停用（写）。停用后 v1 不自动恢复（人工按 decision_trace
 * 复核，planner-decision-kernel-adr）。
 *
 * <p>写侧一律走数据库侧原子语句（建行走 {@code on conflict do nothing}，计数与停用一条
 * UPDATE 求完）：旧实现把行读回 Java 再 +1 写回，并发双点会丢计数（阈值 3 变 2，该停用的
 * 规则停不了），两路同时建行还会抛唯一约束冲突把 500 透给用户。取舍与验证方式见
 * planner-decision-kernel-adr 修订 2。
 */
@Service
public class RuleReputationService {

    private final RuleReputationRepository repository;
    private final PlannerProperties properties;

    public RuleReputationService(RuleReputationRepository repository, PlannerProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 该用户当前已停用的规则键（advisor 据此过滤规则链；无则空集）。 */
    @Transactional(readOnly = true)
    public Set<String> disabledRuleKeys(UUID userId) {
        return repository.findByUserIdAndDisabledTrue(userId).stream()
            .map(RuleReputationEntity::getRuleKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 记一次对某规则的驳回（幂等键 (user, rule_key)）：不存在则建行并计 1，存在则 +1；
     * 累计达 {@code reject-disable-count} 阈值置 disabled。
     *
     * @return 本次驳回后的累计次数（事务内可见，并发增量由行锁串行化）
     */
    @Transactional
    public int recordRejection(UUID userId, String ruleKey) {
        int threshold = Math.max(1, properties.getRejectDisableCount());
        Instant now = Instant.now();
        repository.insertIfAbsent(UUID.randomUUID(), userId, ruleKey, now);
        repository.incrementRejection(userId, ruleKey, threshold, now);
        return repository.rejectedCount(userId, ruleKey);
    }
}
