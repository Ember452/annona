package io.annona.modules.planner.reputation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 规则声誉仓库（P1c-07）。读路径两条：规则链装配取该用户已停用规则；驳回时定位单行 upsert。
 */
public interface RuleReputationRepository extends JpaRepository<RuleReputationEntity, UUID> {

    /** 规则链装配：该用户当前已停用的规则键（disabled=true）。 */
    List<RuleReputationEntity> findByUserIdAndDisabledTrue(UUID userId);

    /** 驳回定位：(user, rule_key) 唯一约束的读取。 */
    Optional<RuleReputationEntity> findByUserIdAndRuleKey(UUID userId, String ruleKey);
}
