package io.annona.modules.planner.reputation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 规则声誉仓库（P1c-07）。写路径全部走<b>数据库侧原子表达式</b>（fencing 同口径）：
 * 驳回计数不读回 Java 再写回——读-改-写在并发双点下会丢计数，且两路同时建行会撞
 * {@code uq_reputation_user_rule} 把 500 抛给用户（09-30 审查发现，planner-adr 修订 2）。
 */
public interface RuleReputationRepository extends JpaRepository<RuleReputationEntity, UUID> {

    /** 规则链装配读路径：该用户已停用的规则行。 */
    List<RuleReputationEntity> findByUserIdAndDisabledTrue(UUID userId);

    /**
     * 首次驳回某规则时建行；已存在则整句无副作用（{@code on conflict do nothing} 不抛异常，
     * 因此同一事务内可以继续做增量——用 try/catch 兜唯一冲突会把事务标成 rollback-only）。
     */
    @Modifying
    @Query(value = "insert into rule_reputation (id, user_id, rule_key, rejected_count, disabled,"
        + " created_at, updated_at) values (:id, :userId, :ruleKey, 0, false, :now, :now)"
        + " on conflict (user_id, rule_key) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
                       @Param("ruleKey") String ruleKey, @Param("now") Instant now);

    /**
     * 计一次驳回：计数 +1 与阈值停用都在同一条 UPDATE 里求值（{@code rejected_count} 取的是
     * 更新前的值，行锁串行化并发增量）。返回 0 = 行不存在（调用方先建行再来）。
     */
    @Modifying
    @Query(value = "update rule_reputation set rejected_count = rejected_count + 1,"
        + " disabled = disabled or (rejected_count + 1 >= :threshold), updated_at = :now"
        + " where user_id = :userId and rule_key = :ruleKey", nativeQuery = true)
    int incrementRejection(@Param("userId") UUID userId, @Param("ruleKey") String ruleKey,
                           @Param("threshold") int threshold, @Param("now") Instant now);

    /** 本事务内可见的累计驳回数（供 Controller 回显"第 N 次驳回/已停用"）。 */
    @Query("select r.rejectedCount from RuleReputationEntity r"
        + " where r.userId = :userId and r.ruleKey = :ruleKey")
    int rejectedCount(@Param("userId") UUID userId, @Param("ruleKey") String ruleKey);
}
