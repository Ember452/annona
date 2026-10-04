package io.annona.modules.study.repository;

import io.annona.modules.study.entity.CheckinEntity;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinRepository extends JpaRepository<CheckinEntity, UUID> {

    /** 一天一条（uq_checkin_user_day）：upsert 按 (user, day) 定位。 */
    Optional<CheckinEntity> findByUserIdAndDay(UUID userId, LocalDate day);

    /** 全量打卡天数（P2-02 小岛解锁数据源；一天一条故即累计打卡次数）。 */
    long countByUserId(UUID userId);
}
