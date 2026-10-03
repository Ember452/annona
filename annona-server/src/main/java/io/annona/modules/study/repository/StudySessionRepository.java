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

    /**
     * P1c-01 信号聚合（shared/signal 门面消费）：单方向窗口内按质量分级的时长求和。
     * 返回单行 {@code [verifiedMinutes, selfReportedMinutes]}（VERIFIED+PARTIAL 归第一列，
     * SELF_REPORTED 归第二列）；无行时 SUM 为 null，由调用方归零。命中索引
     * {@code idx_study_session_user_dir_start}（V2 为本查询预建）。窗口用半开区间
     * {@code [from, toExclusive)} 避免逐行日期转换。
     */
    @Query("select sum(case when s.quality in ('VERIFIED', 'PARTIAL')"
        + " then coalesce(s.minutes, 0) else 0 end),"
        + " sum(case when s.quality = 'SELF_REPORTED' then coalesce(s.minutes, 0) else 0 end)"
        + " from StudySessionEntity s where s.userId = :userId and s.directionId = :directionId"
        + " and s.startAt >= :from and s.startAt < :toExclusive")
    List<Object[]> aggregateQualityByDirection(@Param("userId") UUID userId,
        @Param("directionId") UUID directionId, @Param("from") Instant from,
        @Param("toExclusive") Instant toExclusive);

    /**
     * P2-01 年度热力图日聚合：日界时区注入（AppZones.DAILY）分日 × 质量分桶求和，
     * 返回 {@code [day(java.sql.Date), verifiedMinutes, selfReportedMinutes]}，按日升序。
     * 原生 SQL：timestamptz → 本地日的 {@code at time zone} 转换是 PG 方言，JPQL 表达不了
     * （这正是 docker-it 要钉的真库行为）；用 {@code CAST} 而非 {@code ::}，避免与
     * Hibernate 命名参数的冒号解析相撞。窗口同口径半开 {@code [from, toExclusive)}。
     */
    @Query(value = "select cast(s.start_at at time zone :zone as date) as day,"
        + " sum(case when s.quality in ('VERIFIED', 'PARTIAL') then coalesce(s.minutes, 0) else 0 end),"
        + " sum(case when s.quality = 'SELF_REPORTED' then coalesce(s.minutes, 0) else 0 end)"
        + " from study_session s"
        + " where s.user_id = :userId and s.start_at >= :from and s.start_at < :toExclusive"
        + " group by cast(s.start_at at time zone :zone as date)"
        + " order by cast(s.start_at at time zone :zone as date)", nativeQuery = true)
    List<Object[]> aggregateDailyQuality(@Param("userId") UUID userId, @Param("zone") String zone,
        @Param("from") Instant from, @Param("toExclusive") Instant toExclusive);

    /**
     * P2-01 方向维度聚合：窗口内按方向 × 质量分桶求和，
     * 返回 {@code [directionId, verifiedMinutes, selfReportedMinutes]}。方向名由服务层经
     * DirectionQueryService 只读补齐（归档方向不在 visible 列表，落占位名）。
     */
    @Query("select s.directionId,"
        + " sum(case when s.quality in ('VERIFIED', 'PARTIAL') then coalesce(s.minutes, 0) else 0 end),"
        + " sum(case when s.quality = 'SELF_REPORTED' then coalesce(s.minutes, 0) else 0 end)"
        + " from StudySessionEntity s where s.userId = :userId"
        + " and s.startAt >= :from and s.startAt < :toExclusive group by s.directionId")
    List<Object[]> aggregateQualityPerDirection(@Param("userId") UUID userId,
        @Param("from") Instant from, @Param("toExclusive") Instant toExclusive);
}
