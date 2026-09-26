package io.annona.modules.study.repository;

import io.annona.modules.study.entity.CheckinEntity;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinRepository extends JpaRepository<CheckinEntity, UUID> {

    /** 一天一条（uq_checkin_user_day）：upsert 按 (user, day) 定位。 */
    Optional<CheckinEntity> findByUserIdAndDay(UUID userId, LocalDate day);
}
