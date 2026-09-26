package io.annona.modules.study.repository;

import io.annona.modules.study.entity.StudySessionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudySessionRepository extends JpaRepository<StudySessionEntity, UUID> {

    /** 今日列表：今天开始的会话 + 尚未结束的跨天 RUNNING 会话（午夜前开始的番茄钟不丢）。 */
    @Query("select s from StudySessionEntity s where s.userId = :userId"
        + " and (s.startAt >= :dayStart or s.endAt is null)"
        + " order by s.startAt desc")
    List<StudySessionEntity> findToday(@Param("userId") UUID userId,
        @Param("dayStart") Instant dayStart);

    /** owner 范围内取会话：查不到即 2200，不泄露他人会话存在性（direction findOwned 同口径）。 */
    Optional<StudySessionEntity> findByIdAndUserId(UUID id, UUID userId);

    /** 打卡 hours 联动的会话（ADR §决策 3）：upsert 时按 checkin_id 定位。 */
    Optional<StudySessionEntity> findByCheckinId(UUID checkinId);
}
