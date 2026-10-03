package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.support.AppZones;
import io.annona.modules.study.dto.StatsOverviewResponse;
import io.annona.modules.study.service.StudyStatsService;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 年度统计在<b>真 PG</b> 上的集测（CI docker-it 专属，本机无 PG 不跑）。
 *
 * <p>本机测不到而必须真库钉的：① {@code at time zone} 原生聚合的时区日界归属——
 * 同一时刻在 Asia/Shanghai 与 UTC 落在不同日期，JPQL 表达不了该转换，mock 探不到错；
 * ② 原生 SQL 的 CAST 写法与命名参数共存（:: 形态会与 Hibernate 冒号解析相撞）；
 * ③ 年度窗口半开边界：上一年来、今年初的会话各归各的年份。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("年度统计真库集测（P2-01）")
class StudyStatsFlowIT {

    @Autowired
    private StudyStatsService statsService;
    @Autowired
    private DirectionRepository directionRepository;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID userId;
    private UUID directionId;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(dataSource);
        userId = UUID.randomUUID();
        jdbc.update("insert into app_user (id, email, password_hash, status, role)"
            + " values (?, ?, 'it-not-a-real-hash', 'ACTIVE', 'USER')",
            userId, "it-stats-" + userId + "@annona.test");
        DirectionEntity direction = new DirectionEntity();
        direction.setId(UUID.randomUUID());
        direction.setUserId(userId);
        direction.setKey("stats-it");
        direction.setName("统计集测方向");
        direction.setOrigin("USER_CUSTOM");
        direction.setStatus("ACTIVE");
        directionRepository.saveAndFlush(direction);
        directionId = direction.getId();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from app_user where id = ?", userId);
    }

    private void insertSession(String startAtOffsetUtc, int minutes, String quality) {
        jdbc.update("insert into study_session (id, user_id, direction_id, mode, start_at,"
                + " end_at, minutes, quality) values (?, ?, ?, 'POMODORO',"
                + " (now() at time zone 'utc') + ?::interval,"
                + " (now() at time zone 'utc') + ?::interval, ?, ?)",
            UUID.randomUUID(), userId, directionId, startAtOffsetUtc, startAtOffsetUtc,
            minutes, quality);
    }

    @Test
    @DisplayName("日聚合按 Asia/Shanghai 日界归属，质量分桶不混算")
    void dailyAggregationRespectsZoneAndQuality() {
        ZoneId zone = AppZones.DAILY;
        LocalDate day1 = LocalDate.now(zone);
        // 当日 22:00 +08 → 属当日；次日凌晨 00:30 +08 → 属次日
        insertShanghai(day1, 22, 0, 30, "VERIFIED");
        insertShanghai(day1.plusDays(1), 0, 30, 20, "SELF_REPORTED");

        StatsOverviewResponse response = statsService.overview(userId, day1.getYear());

        List<StatsOverviewResponse.DayMinutes> days = response.days();
        assertThat(days).hasSize(2);
        assertThat(days.get(0).day()).isEqualTo(day1);
        assertThat(days.get(0).verifiedMinutes()).isEqualTo(30);
        assertThat(days.get(0).selfReportedMinutes()).isZero();
        assertThat(days.get(1).day()).isEqualTo(day1.plusDays(1));
        assertThat(days.get(1).verifiedMinutes()).isZero();
        assertThat(days.get(1).selfReportedMinutes()).isEqualTo(20);
        // fresh 用户：无打卡行，全量口径为 0
        assertThat(response.totalCheckins()).isZero();
    }

    @Test
    @DisplayName("年度窗口半开：去年最后一天的会话不进今年，年初第一刻进今年")
    void yearWindowIsHalfOpen() {
        ZoneId zone = AppZones.DAILY;
        int year = LocalDate.now(zone).getYear() - 1;
        // 上一年 12-31 23:30 +08 → 属上一年，不在本年窗口
        insertShanghai(LocalDate.of(year, 12, 31), 23, 30, 45, "VERIFIED");
        // 本年 01-01 00:10 +08 → 属本年
        insertShanghai(LocalDate.of(year + 1, 1, 1), 0, 10, 25, "PARTIAL");

        StatsOverviewResponse response = statsService.overview(userId, year + 1);

        assertThat(response.days()).hasSize(1);
        assertThat(response.days().get(0).day()).isEqualTo(LocalDate.of(year + 1, 1, 1));
        assertThat(response.days().get(0).verifiedMinutes()).isEqualTo(25);
    }

    @Test
    @DisplayName("方向聚合带回方向名")
    void directionAggregationCarriesName() {
        ZoneId zone = AppZones.DAILY;
        LocalDate day = LocalDate.now(zone);
        insertShanghai(day, 9, 0, 60, "VERIFIED");

        StatsOverviewResponse response = statsService.overview(userId, day.getYear());

        assertThat(response.directions()).hasSize(1);
        assertThat(response.directions().get(0).directionId()).isEqualTo(directionId.toString());
        assertThat(response.directions().get(0).name()).isEqualTo("统计集测方向");
        assertThat(response.directions().get(0).verifiedMinutes()).isEqualTo(60);
    }

    /** 以 Asia/Shanghai 指定日期的 HH:mm 落一条会话（start=end），直接写绝对时刻。 */
    private void insertShanghai(LocalDate day, int hour, int minute, int minutes, String quality) {
        var start = day.atTime(hour, minute).atZone(AppZones.DAILY).toInstant();
        jdbc.update("insert into study_session (id, user_id, direction_id, mode, start_at,"
                + " end_at, minutes, quality) values (?, ?, ?, 'POMODORO', ?, ?, ?, ?)",
            UUID.randomUUID(), userId, directionId, start, start.plusSeconds(60), minutes,
            quality);
    }
}
