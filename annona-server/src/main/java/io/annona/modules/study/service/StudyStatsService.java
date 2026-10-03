package io.annona.modules.study.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.support.AppZones;
import io.annona.modules.study.dto.StatsOverviewResponse;
import io.annona.modules.study.dto.StatsOverviewResponse.DayMinutes;
import io.annona.modules.study.dto.StatsOverviewResponse.DirectionMinutes;
import io.annona.modules.study.repository.CheckinRepository;
import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.service.DirectionQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 年度学习统计（P2-01，只读）：日聚合 + 方向聚合，一次查询各一，不循环查库。
 *
 * <p>口径红线：与 {@code StudySignalPortImpl} 同源——VERIFIED+PARTIAL 为"有效专注"、
 * SELF_REPORTED 单独分列，热力图上分色展示而非混算；日界统一 {@link AppZones#DAILY}。
 * 服务端只回原始日聚合；连续天数/日均等派生口径在前端 lib/statsView 纯函数里算。
 */
@Service
public class StudyStatsService {

    private static final ZoneId ZONE = AppZones.DAILY;
    /** 年份合法域：防止任意年份扫描全表（表中数据远新于此，越界即参数错误）。 */
    private static final int MIN_YEAR = 2020;
    private static final int MAX_YEAR = 2100;
    /** 归档方向不在 visible 列表，历史时长仍须可见——落占位名而非丢行。 */
    private static final String ARCHIVED_DIRECTION_NAME = "已归档方向";

    private final StudySessionRepository sessionRepository;
    private final CheckinRepository checkinRepository;
    private final DirectionQueryService directions;

    public StudyStatsService(StudySessionRepository sessionRepository,
                             CheckinRepository checkinRepository,
                             DirectionQueryService directions) {
        this.sessionRepository = sessionRepository;
        this.checkinRepository = checkinRepository;
        this.directions = directions;
    }

    @Transactional(readOnly = true)
    public StatsOverviewResponse overview(UUID userId, Integer year) {
        int resolved = year != null ? year : LocalDate.now(ZONE).getYear();
        if (resolved < MIN_YEAR || resolved > MAX_YEAR) {
            throw new BusinessException(ErrorCode.STUDY_SESSION_INVALID_RANGE,
                "年份需在 " + MIN_YEAR + "–" + MAX_YEAR + " 之间");
        }
        Instant from = LocalDate.of(resolved, 1, 1).atStartOfDay(ZONE).toInstant();
        Instant toExclusive = LocalDate.of(resolved + 1, 1, 1).atStartOfDay(ZONE).toInstant();

        List<DayMinutes> days = sessionRepository
            .aggregateDailyQuality(userId, ZONE.getId(), from, toExclusive)
            .stream()
            .map(row -> new DayMinutes(((java.sql.Date) row[0]).toLocalDate(),
                minutes(row[1]), minutes(row[2])))
            .toList();

        List<DirectionMinutes> directionTotals = new ArrayList<>();
        List<Object[]> rows = sessionRepository.aggregateQualityPerDirection(userId, from, toExclusive);
        if (!rows.isEmpty()) {
            Map<UUID, String> names = directionNames(userId);
            for (Object[] row : rows) {
                UUID directionId = (UUID) row[0];
                directionTotals.add(new DirectionMinutes(directionId.toString(),
                    names.getOrDefault(directionId, ARCHIVED_DIRECTION_NAME),
                    minutes(row[1]), minutes(row[2])));
            }
            directionTotals.sort(Comparator.comparingLong(DirectionMinutes::verifiedMinutes)
                .reversed());
        }
                // 全量打卡数（跨年）：小岛解锁的唯一生长变量，年窗口装不下它
        long totalCheckins = checkinRepository.countByUserId(userId);
        return new StatsOverviewResponse(resolved, days, directionTotals, totalCheckins);
    }

    /** visible 方向 id → 名称；只读消费方向字典（无白名单路径）。 */
    private Map<UUID, String> directionNames(UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        for (DirectionResponse direction : directions.listVisible(userId.toString())) {
            names.put(UUID.fromString(direction.id()), direction.name());
        }
        return names;
    }

    /** SUM 对空集返回 null（或 0）：统一转非负分钟数。 */
    private static long minutes(Object sum) {
        return sum instanceof Number n ? Math.max(0L, n.longValue()) : 0L;
    }
}
