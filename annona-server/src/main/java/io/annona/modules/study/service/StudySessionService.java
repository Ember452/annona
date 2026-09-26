package io.annona.modules.study.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.study.HeartbeatTimeline;
import io.annona.modules.study.dto.ManualSessionRequest;
import io.annona.modules.study.dto.SessionResponse;
import io.annona.modules.study.dto.StartSessionRequest;
import io.annona.modules.study.entity.StudyEventEntity;
import io.annona.modules.study.entity.StudySessionEntity;
import io.annona.modules.study.mapper.StudyMapper;
import io.annona.modules.study.quality.QualityGrader;
import io.annona.modules.study.repository.StudyEventRepository;
import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.direction.service.DirectionQueryService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学习会话编排（start / heartbeat / blur / finish / manual / today）。
 *
 * <p>事务边界：start 与 recordBlur / createManual 是纯 DB 写，@Transactional 包住；
 * <b>finish 刻意无 @Transactional</b>——Redis 时间线读取在事务外，先落会话终态再落事件
 * （事件是审计留痕、无消费方，写失败只损一条留痕，不影响 minutes/quality 正确性），
 * 两写不强行同事务以守住"事务范围最小"（AGENTS §4）。
 *
 * <p>归属校验口径与 direction 一致：非本人会话按不存在处理（2200），不泄露存在性。
 */
@Service
public class StudySessionService {

    private static final int MAX_PLANNED_MINUTES = 240;
    private static final Duration MAX_MANUAL_SPAN = Duration.ofHours(24);
    /** "今日"口径固定 Asia/Shanghai（ADR §后果；per-user 时区推迟到有海外用户需求）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StudySessionRepository sessionRepository;
    private final StudyEventRepository eventRepository;
    private final HeartbeatTimeline heartbeats;
    private final DirectionQueryService directions;
    private final StudyMapper mapper;

    public StudySessionService(StudySessionRepository sessionRepository,
                               StudyEventRepository eventRepository,
                               HeartbeatTimeline heartbeats,
                               DirectionQueryService directions,
                               StudyMapper mapper) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.heartbeats = heartbeats;
        this.directions = directions;
        this.mapper = mapper;
    }

    @Transactional
    public SessionResponse start(String userId, StartSessionRequest request) {
        requireVisibleDirection(userId, request.directionId());
        Integer planned = request.plannedMinutes();
        if (planned == null || planned < 1 || planned > MAX_PLANNED_MINUTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "计划专注分钟需为 1–240，实际收到：" + planned);
        }
        StudySessionEntity entity = new StudySessionEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(UUID.fromString(userId));
        entity.setDirectionId(UUID.fromString(request.directionId()));
        entity.setMode(StudySessionEntity.MODE_POMODORO);
        entity.setStartAt(Instant.now());
        sessionRepository.save(entity);
        saveEvent(entity.getId(), StudyEventEntity.TYPE_START);
        return mapper.toResponse(entity);
    }

    /** 心跳只证明活着：落 Redis 时间线，不产生 DB 写（ADR §决策 1）。 */
    public void heartbeat(String userId, String sessionId) {
        StudySessionEntity entity = findOwned(userId, sessionId);
        if (entity.getEndAt() != null) {
            throw new BusinessException(ErrorCode.STUDY_SESSION_ALREADY_FINISHED);
        }
        heartbeats.touch(entity.getId(), Instant.now());
    }

    @Transactional
    public void recordBlur(String userId, String sessionId, String reportedType) {
        if (!StudyEventEntity.TYPE_BLUR.equals(reportedType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅接受 BLUR 事件上报");
        }
        StudySessionEntity entity = findOwned(userId, sessionId);
        if (entity.getEndAt() != null) {
            throw new BusinessException(ErrorCode.STUDY_SESSION_ALREADY_FINISHED);
        }
        saveEvent(entity.getId(), StudyEventEntity.TYPE_BLUR);
    }

    public SessionResponse finish(String userId, String sessionId, boolean abandon) {
        StudySessionEntity entity = findOwned(userId, sessionId);
        if (entity.getEndAt() != null) {
            // 二次 finish 幂等：已终态直接返回现结果，不重复判定
            return mapper.toResponse(entity);
        }
        List<Instant> timeline = heartbeats.timeline(entity.getId());
        Instant endAt = Instant.now();
        QualityGrader.GradeResult grade = QualityGrader.grade(entity.getStartAt(), endAt, timeline);
        entity.setEndAt(endAt);
        entity.setMinutes(grade.minutes());
        entity.setQuality(grade.quality());
        sessionRepository.save(entity);
        saveEvent(entity.getId(), abandon ? StudyEventEntity.TYPE_INTERRUPT : StudyEventEntity.TYPE_FINISH);
        heartbeats.evict(entity.getId());
        return mapper.toResponse(entity);
    }

    @Transactional
    public SessionResponse createManual(String userId, ManualSessionRequest request) {
        requireVisibleDirection(userId, request.directionId());
        if (request.startAt() == null || request.endAt() == null
            || !request.endAt().isAfter(request.startAt())) {
            throw new BusinessException(ErrorCode.STUDY_SESSION_INVALID_RANGE, "补录时间范围无效");
        }
        if (request.startAt().plus(MAX_MANUAL_SPAN).isBefore(request.endAt())) {
            throw new BusinessException(ErrorCode.STUDY_SESSION_INVALID_RANGE, "补录跨度不得超过 24 小时");
        }
        int minutes = (int) Math.max(1, Math.round(
            Duration.between(request.startAt(), request.endAt()).toMillis() / 60000.0));
        StudySessionEntity entity = new StudySessionEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(UUID.fromString(userId));
        entity.setDirectionId(UUID.fromString(request.directionId()));
        entity.setMode(StudySessionEntity.MODE_CHECKIN);
        entity.setStartAt(request.startAt());
        entity.setEndAt(request.endAt());
        entity.setMinutes(minutes);
        entity.setQuality(StudySessionEntity.QUALITY_SELF_REPORTED);
        return mapper.toResponse(sessionRepository.save(entity));
    }

    /** 今日会话（Asia/Shanghai 口径）：含进行中，minutes/quality 为 null。 */
    public List<SessionResponse> today(String userId) {
        Instant dayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        return sessionRepository.findToday(UUID.fromString(userId), dayStart).stream()
            .map(mapper::toResponse)
            .toList();
    }

    private void requireVisibleDirection(String userId, String directionId) {
        if (!directions.existsVisibleTo(userId, directionId)) {
            throw new BusinessException(ErrorCode.DIRECTION_NOT_FOUND);
        }
    }

    private StudySessionEntity findOwned(String userId, String sessionId) {
        UUID id = parseUuid(sessionId);
        return sessionRepository.findByIdAndUserId(id, UUID.fromString(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.STUDY_SESSION_NOT_FOUND));
    }

    private void saveEvent(UUID sessionId, String type) {
        StudyEventEntity event = new StudyEventEntity();
        event.setId(UUID.randomUUID());
        event.setSessionId(sessionId);
        event.setType(type);
        event.setAt(Instant.now());
        eventRepository.save(event);
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "需为合法 UUID，实际收到：" + value);
        }
    }
}
