package io.annona.modules.study.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.support.AppZones;
import io.annona.modules.study.dto.CheckinResponse;
import io.annona.modules.study.dto.UpsertCheckinRequest;
import io.annona.modules.study.entity.CheckinEntity;
import io.annona.modules.study.entity.StudySessionEntity;
import io.annona.modules.study.mapper.StudyMapper;
import io.annona.modules.study.repository.CheckinRepository;
import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.domain.CheckinLinkedEvent;
import io.annona.shared.direction.service.DirectionQueryService;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日打卡（一天一条幂等 upsert，ADR §决策 4）。
 *
 * <p>hours &gt; 0 时同事务联动一条 mode=CHECKIN / quality=SELF_REPORTED 的会话，
 * 打卡更新按 {@code study_session.checkin_id} 定位同步；hours 归 0 则删除联动会话——
 * study_session 是时长的单一真相源，P1c 信号聚合只读它（ADR §决策 3）。
 *
 * <p>同 user×day 的并发 upsert 由事务级 advisory lock 串行化（保 plan-module-adr §决策 3
 * “联动事件只发一次”的判定可靠）；本方法必须在事务内调用（锁随事务释放）。
 */
@Service
public class CheckinService {

    private static final BigDecimal MAX_HOURS = new BigDecimal("24");
    private static final int MAX_MOOD_LENGTH = 32;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final int MAX_SNAPSHOT_URL_LENGTH = 255;
    /** 打卡日界与学习侧“今日”同一口径（全仓单一出处，见 {@link AppZones}）。 */
    private static final ZoneId ZONE = AppZones.DAILY;

    private final CheckinRepository checkinRepository;
    private final StudySessionRepository sessionRepository;
    private final DirectionQueryService directions;
    private final StudyMapper mapper;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public CheckinService(CheckinRepository checkinRepository,
                          StudySessionRepository sessionRepository,
                          DirectionQueryService directions,
                          StudyMapper mapper,
                          EntityManager entityManager,
                          ApplicationEventPublisher eventPublisher) {
        this.checkinRepository = checkinRepository;
        this.sessionRepository = sessionRepository;
        this.directions = directions;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CheckinResponse upsertToday(String userId, UpsertCheckinRequest request) {
        if (!directions.existsVisibleTo(userId, request.directionId())) {
            throw new BusinessException(ErrorCode.DIRECTION_NOT_FOUND);
        }
        BigDecimal hours = request.hours() == null ? BigDecimal.ZERO : request.hours();
        if (hours.signum() < 0 || hours.compareTo(MAX_HOURS) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "学习时长需为 0–24 小时");
        }
        // 与列 NUMERIC(4,1) 同精度归一：否则 1.25 会被 PG 舍成 1.3，而联动会话用 1.25×60=75，
        // 两个"真相源"差 3 分钟（评审 A2）。先归一再算 minutes，单一口径。
        hours = hours.setScale(1, RoundingMode.HALF_UP);
        if (request.energy() != null && (request.energy() < 1 || request.energy() > 5)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "能量值需为 1–5");
        }
        requireLength("心情", request.mood(), MAX_MOOD_LENGTH);
        requireLength("备注", request.note(), MAX_NOTE_LENGTH);
        requireLength("截图地址", request.snapshotUrl(), MAX_SNAPSHOT_URL_LENGTH);

        UUID owner = UUID.fromString(userId);
        UUID directionId = UUID.fromString(request.directionId());
        LocalDate today = LocalDate.now(ZONE);

        // 首建判定的竞态防线（2026-10-03 P2 审查）：READ COMMITTED 下“查后插”两事务可互
        // 不可见，双发 CheckinLinkedEvent 让进度重复累计。唯一约束只防重复行不防事件；
        // 用事务级 advisory lock 把同 user×day 的 upsert 串行化（取舍见 lockUserDay）。
        lockUserDay(owner, today);
        Optional<CheckinEntity> found = checkinRepository.findByUserIdAndDay(owner, today);
        boolean firstInsert = found.isEmpty();
        CheckinEntity entity = found.orElseGet(() -> {
            CheckinEntity fresh = new CheckinEntity();
            fresh.setId(UUID.randomUUID());
            fresh.setUserId(owner);
            fresh.setDay(today);
            return fresh;
        });
        entity.setDirectionId(directionId);
        entity.setHours(hours);
        entity.setMood(request.mood());
        entity.setEnergy(request.energy());
        entity.setNote(request.note());
        entity.setSnapshotUrl(request.snapshotUrl());
        // created_at 是 insertable=false + DB DEFAULT：save 后 flush + refresh 回读，
        // 令 POST 与 GET 同形（P1a-03 DirectionCommandService 同款修法，评审 A1）。
        // 预置主键实体的 save() 走 merge，返回的才是受管副本——必须接住返回值再 refresh；
        // 直接 refresh 原引用必抛 DetachedObjectException（CI 真 PG 集测实测，slice 测的
        // mock EM 探不到）。
        entity = checkinRepository.saveAndFlush(entity);
        entityManager.refresh(entity);

        syncLinkedSession(entity);
        // 打卡联动（plan-module-adr §决策 3）：仅首建且 hours>0 时发布——当日改 hours
        // 不补发事件（幂等靠"只发一次"，差额重算被 ADR 否决）。监听器在 AFTER_COMMIT 执行。
        if (firstInsert && hours.signum() > 0) {
            eventPublisher.publishEvent(new CheckinLinkedEvent(owner, directionId,
                entity.getHours().multiply(BigDecimal.valueOf(60)).intValue(), today, entity.getId()));
        }
        return mapper.toResponse(entity);
    }

    /** 今天还没打过返回 null（前端据此切换"首次打卡"态）。 */
    public CheckinResponse today(String userId) {
        return checkinRepository.findByUserIdAndDay(UUID.fromString(userId), LocalDate.now(ZONE))
            .map(mapper::toResponse)
            .orElse(null);
    }

    /**
     * 事务级 advisory lock（键 = user×day 哈希）：同用户同日并发 upsert 串行化，随事务提交
     * 自动释放；阻塞而非失败，无需重试。选锁而非 upsert-RETURNING：后者绕过 JPA 实体
     * 生命周期，拆掉 saveAndFlush+refresh 受管语义。PG 专属（storage-single-postgres-adr）。
     *
     * <p>必须走 EntityManager 且用 {@code getResultList}：{@code pg_advisory_xact_lock} 返回
     * void，作为查询没有可映射的行——getSingleResult 会招 NoResultException；也不能标
     * {@code @Modifying}（走 executeUpdate，驱动对返回结果集的语句报
     * "A result was returned when none was expected"，CI docker-it 实测）。
     */
    private void lockUserDay(UUID owner, LocalDate day) {
        entityManager.createNativeQuery(
                "SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))")
            .setParameter("k", owner + "@" + day)
            .getResultList();
    }

    private void syncLinkedSession(CheckinEntity checkin) {
        BigDecimal hours = checkin.getHours();
        if (hours.signum() > 0) {
            StudySessionEntity session = sessionRepository.findByCheckinId(checkin.getId())
                .orElseGet(() -> {
                    StudySessionEntity fresh = new StudySessionEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setUserId(checkin.getUserId());
                    fresh.setCheckinId(checkin.getId());
                    fresh.setMode(StudySessionEntity.MODE_CHECKIN);
                    fresh.setQuality(StudySessionEntity.QUALITY_SELF_REPORTED);
                    return fresh;
                });
            int minutes = hours.multiply(BigDecimal.valueOf(60)).intValue();
            Instant startAt = checkin.getDay().atStartOfDay(ZONE).toInstant();
            session.setDirectionId(checkin.getDirectionId());
            session.setStartAt(startAt);
            session.setEndAt(startAt.plus(Duration.ofMinutes(minutes)));
            session.setMinutes(minutes);
            sessionRepository.save(session);
        } else {
            // 时长归 0 = 用户收回补录；联动会话一并移除，避免悬空的 SELF_REPORTED 时长
            sessionRepository.findByCheckinId(checkin.getId())
                .ifPresent(sessionRepository::delete);
        }
    }

    private void requireLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + "不得超过 " + max + " 字符");
        }
    }
}
