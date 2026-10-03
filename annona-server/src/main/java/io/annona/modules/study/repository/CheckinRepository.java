package io.annona.modules.study.repository;

import io.annona.modules.study.entity.CheckinEntity;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckinRepository extends JpaRepository<CheckinEntity, UUID> {

    /** 一天一条（uq_checkin_user_day）：upsert 按 (user, day) 定位。 */
    Optional<CheckinEntity> findByUserIdAndDay(UUID userId, LocalDate day);

    /**
     * 事务级 advisory lock（键 = user×day 哈希）：同用户同日的并发 upsert 串行化，
     * 保证“首建判定→插入”无竞态窗（CheckinLinkedEvent 只发一次的 DB 仲裁）。
     * 选它而非 upsert-RETURNING：后者绕过 JPA 实体生命周期，会拆掉 saveAndFlush+refresh
     * 的受管语义；锁方案零 SQL 重写且锁随事务提交自动释放。阻塞非失败，无需重试。
     * PG 专属（存储已收敛 PostgreSQL，storage-single-postgres-adr）；须在有事务的调用点执行。
     */
    @Modifying
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:userId || '@' || :day, 0))",
        nativeQuery = true)
    void lockUserDay(@Param("userId") String userId, @Param("day") String day);

    /** 全量打卡天数（P2-02 小岛解锁数据源；一天一条故即累计打卡次数）。 */
    long countByUserId(UUID userId);
}
