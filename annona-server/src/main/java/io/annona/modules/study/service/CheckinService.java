package io.annona.modules.study.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.study.dto.CheckinResponse;
import io.annona.modules.study.dto.UpsertCheckinRequest;
import io.annona.modules.study.entity.CheckinEntity;
import io.annona.modules.study.entity.StudySessionEntity;
import io.annona.modules.study.mapper.StudyMapper;
import io.annona.modules.study.repository.CheckinRepository;
import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.direction.service.DirectionQueryService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日打卡（一天一条幂等 upsert，ADR §决策 4）。
 *
 * <p>hours &gt; 0 时同事务联动一条 mode=CHECKIN / quality=SELF_REPORTED 的会话，
 * 打卡更新按 {@code study_session.checkin_id} 定位同步；hours 归 0 则删除联动会话——
 * study_session 是时长的单一真相源，P1c 信号聚合只读它（ADR §决策 3）。
 */
@Service
public class CheckinService {

    private static final BigDecimal MAX_HOURS = new BigDecimal("24");
    private static final int MAX_MOOD_LENGTH = 32;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final int MAX_SNAPSHOT_URL_LENGTH = 255;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CheckinRepository checkinRepository;
    private final StudySessionRepository sessionRepository;
    private final DirectionQueryService directions;
    private final StudyMapper mapper;

    public CheckinService(CheckinRepository checkinRepository,
                          StudySessionRepository sessionRepository,
                          DirectionQueryService directions,
                          StudyMapper mapper) {
        this.checkinRepository = checkinRepository;
        this.sessionRepository = sessionRepository;
        this.directions = directions;
        this.mapper = mapper;
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
        if (request.energy() != null && (request.energy() < 1 || request.energy() > 5)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "能量值需为 1–5");
        }
        requireLength("心情", request.mood(), MAX_MOOD_LENGTH);
        requireLength("备注", request.note(), MAX_NOTE_LENGTH);
        requireLength("截图地址", request.snapshotUrl(), MAX_SNAPSHOT_URL_LENGTH);

        UUID owner = UUID.fromString(userId);
        UUID directionId = UUID.fromString(request.directionId());
        LocalDate today = LocalDate.now(ZONE);

        CheckinEntity entity = checkinRepository.findByUserIdAndDay(owner, today)
            .orElseGet(() -> {
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
        checkinRepository.save(entity);

        syncLinkedSession(entity, hours);
        return mapper.toResponse(entity);
    }

    /** 今天还没打过返回 null（前端据此切换"首次打卡"态）。 */
    public CheckinResponse today(String userId) {
        return checkinRepository.findByUserIdAndDay(UUID.fromString(userId), LocalDate.now(ZONE))
            .map(mapper::toResponse)
            .orElse(null);
    }

    private void syncLinkedSession(CheckinEntity checkin, BigDecimal hours) {
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
