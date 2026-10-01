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
     * 记一次对某规则的驳回（幂等键 (user, rule_key)）：不存在则新建计数 1，存在则 +1；
     * 累计达 {@code reject-disable-count} 阈值置 disabled。返回停用后的总驳回数。
     */
    @Transactional
    public int recordRejection(UUID userId, String ruleKey) {
        int threshold = Math.max(1, properties.getRejectDisableCount());
        RuleReputationEntity entity = repository.findByUserIdAndRuleKey(userId, ruleKey)
            .orElseGet(() -> RuleReputationEntity.fresh(userId, ruleKey, Instant.now()));
        entity.recordRejection(threshold, Instant.now());
        repository.save(entity);
        return entity.getRejectedCount();
    }
}
