package io.annona.modules.study.signal;

import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.study.StudySignal;
import io.annona.shared.study.StudySignalPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link StudySignalPort} 的 study 实现（P1c-01）：单方向窗口内的质量分级时长聚合，
 * 单条聚合查询命中 {@code idx_study_session_user_dir_start}，不循环查库。
 *
 * <p>日界转换用 Asia/Shanghai（V1 user_profile.timezone 默认值、应用规范时区），
 * 使 {@code [from,to]} 闭区间落成 {@code [from 00:00, to+1 00:00)} 半开区间比较 start_at。
 */
@Service
public class StudySignalPortImpl implements StudySignalPort {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StudySessionRepository studySessionRepository;

    public StudySignalPortImpl(StudySessionRepository studySessionRepository) {
        this.studySessionRepository = studySessionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public StudySignal studySignal(UUID userId, UUID directionId, LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay(ZONE).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<Object[]> rows = studySessionRepository
            .aggregateQualityByDirection(userId, directionId, fromInstant, toExclusive);
        if (rows.isEmpty() || rows.get(0) == null) {
            return new StudySignal(Duration.ZERO, Duration.ZERO);
        }
        Object[] row = rows.get(0);
        return new StudySignal(minutes(row[0]), minutes(row[1]));
    }

    /** SUM 对空集返回 null（或 0）：统一转非负分钟 Duration。 */
    private static Duration minutes(Object sum) {
        long value = sum instanceof Number n ? Math.max(0L, n.longValue()) : 0L;
        return Duration.ofMinutes(value);
    }
}
