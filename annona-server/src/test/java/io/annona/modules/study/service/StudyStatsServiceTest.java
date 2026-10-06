package io.annona.modules.study.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.support.AppZones;
import io.annona.modules.study.dto.StatsOverviewResponse;
import io.annona.modules.study.repository.CheckinRepository;
import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.service.DirectionQueryService;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link StudyStatsService} 切片：行形状 → DTO 映射、缺省年份、越界年份、
 * 归档方向占位名与排序。SQL 本身（时区分桶）由 {@code StudyStatsFlowIT} 在真库钉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("年度统计服务切片")
class StudyStatsServiceTest {

    @Mock
    private StudySessionRepository sessionRepository;

    @Mock
    private CheckinRepository checkinRepository;

    @Mock
    private DirectionQueryService directions;

    @InjectMocks
    private StudyStatsService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID directionA = UUID.randomUUID();
    private final UUID directionB = UUID.randomUUID();

    @Nested
    @DisplayName("年份参数")
    class YearParam {

        @Test
        @DisplayName("缺省取 AppZones.DAILY 当日年份")
        void defaultsToCurrentYear() {
            when(sessionRepository.aggregateDailyQuality(eq(userId), any(), any(), any()))
                .thenReturn(List.of());
            when(sessionRepository.aggregateQualityPerDirection(eq(userId), any(), any()))
                .thenReturn(List.of());

            StatsOverviewResponse response = service.overview(userId, null);

            assertThat(response.year())
                .isEqualTo(LocalDate.now(AppZones.DAILY).getYear());
        }

        @Test
        @DisplayName("越界年份抛 2202")
        void rejectsOutOfRangeYear() {
            assertThatThrownBy(() -> service.overview(userId, 1999))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2202));
            assertThatThrownBy(() -> service.overview(userId, 2101))
                .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("行映射")
    class RowMapping {

        @Test
        @DisplayName("日聚合行转 DTO（LocalDate 与 java.sql.Date 两型都收）；SUM 空集 null 归零")
        void mapsDailyRows() {
            Instant from = Instant.EPOCH;
            when(sessionRepository.aggregateDailyQuality(eq(userId), eq("Asia/Shanghai"), any(), any()))
                .thenReturn(List.of(
                    // Hibernate 7 原生查询 date 标量默认给 LocalDate（真库集测付过学费的形状）
                    new Object[]{LocalDate.of(2026, 1, 10), 90L, 15L},
                    // Hibernate 6.x（prefer_jdbc_datetime_types=true）给 java.sql.Date——转换两条路都收
                    new Object[]{Date.valueOf("2026-01-11"), null, null}));
            when(sessionRepository.aggregateQualityPerDirection(eq(userId), any(), any()))
                .thenReturn(List.of());

            StatsOverviewResponse response = service.overview(userId, 2026);

            assertThat(response.days()).hasSize(2);
            assertThat(response.days().get(0).day()).isEqualTo(LocalDate.of(2026, 1, 10));
            assertThat(response.days().get(0).verifiedMinutes()).isEqualTo(90L);
            assertThat(response.days().get(0).selfReportedMinutes()).isEqualTo(15L);
            assertThat(response.days().get(1).verifiedMinutes()).isZero();
            assertThat(response.days().get(1).selfReportedMinutes()).isZero();
        }

        @Test
        @DisplayName("方向聚合按有效时长降序；归档方向落占位名而非丢行")
        void mapsDirectionRowsWithArchivedFallback() {
            when(sessionRepository.aggregateDailyQuality(eq(userId), any(), any(), any()))
                .thenReturn(List.of());
            when(sessionRepository.aggregateQualityPerDirection(eq(userId), any(), any()))
                .thenReturn(List.of(
                    new Object[]{directionA, 30L, 5L},
                    new Object[]{directionB, 120L, 0L}));
            when(directions.listVisible(eq(userId.toString()))).thenReturn(List.of(
                new DirectionResponse(directionB.toString(), "b", "并发编程",
                    "SKILL_BUILTIN", null, "ACTIVE", Instant.EPOCH)));

            StatsOverviewResponse response = service.overview(userId, 2026);

            assertThat(response.directions()).hasSize(2);
            assertThat(response.directions().get(0).name()).isEqualTo("并发编程");
            assertThat(response.directions().get(0).verifiedMinutes()).isEqualTo(120L);
            assertThat(response.directions().get(1).name()).isEqualTo("已归档方向");
            assertThat(response.directions().get(1).verifiedMinutes()).isEqualTo(30L);
        }

        @Test
        @DisplayName("无聚合行时 days 与 directions 为空列表而非 null；全量打卡数透传")
        void emptyResult() {
            when(sessionRepository.aggregateDailyQuality(eq(userId), any(), any(), any()))
                .thenReturn(List.of());
            when(sessionRepository.aggregateQualityPerDirection(eq(userId), any(), any()))
                .thenReturn(List.of());
            when(checkinRepository.countByUserId(userId)).thenReturn(42L);

            StatsOverviewResponse response = service.overview(userId, 2026);

            assertThat(response.days()).isEmpty();
            assertThat(response.directions()).isEmpty();
            // 全量口径（跨年）与年窗口数据并存，小岛解锁吃这个数
            assertThat(response.totalCheckins()).isEqualTo(42L);
        }
    }
}
