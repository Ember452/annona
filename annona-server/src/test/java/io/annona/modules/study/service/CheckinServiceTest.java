package io.annona.modules.study.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.modules.study.dto.CheckinResponse;
import io.annona.modules.study.dto.UpsertCheckinRequest;
import io.annona.modules.study.entity.CheckinEntity;
import io.annona.modules.study.entity.StudySessionEntity;
import io.annona.modules.study.mapper.StudyMapperImpl;
import io.annona.modules.study.repository.CheckinRepository;
import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.domain.CheckinLinkedEvent;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CheckinService 的 Mockito 切片：一天一条幂等 upsert（study-collection-adr §决策 4）与
 * hours 联动会话的单一真相源规则——首打建联动、重打原地更新并同步 minutes、归 0 删联动。
 * mapper 用 MapStruct 真实现，repository / DirectionQueryService 全 mock。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("CheckinService：打卡幂等 upsert 与联动会话")
class CheckinServiceTest {

    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String DIRECTION_ID = "11111111-1111-1111-1111-111111111111";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private CheckinRepository checkinRepository;

    @Mock
    private StudySessionRepository sessionRepository;

    @Mock
    private DirectionQueryService directions;

    @Mock
    private EntityManager entityManager;

    private CheckinService service;

    @BeforeEach
    void setUp() {
        service = new CheckinService(checkinRepository, sessionRepository,
            directions, new StudyMapperImpl(), entityManager, event -> {
            });
    }

    private CheckinEntity todayCheckin() {
        CheckinEntity entity = new CheckinEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(UUID.fromString(OWNER));
        entity.setDirectionId(UUID.fromString(DIRECTION_ID));
        entity.setDay(LocalDate.now(ZONE));
        return entity;
    }

    private StudySessionEntity linkedSession(CheckinEntity checkin) {
        StudySessionEntity entity = new StudySessionEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(checkin.getUserId());
        entity.setCheckinId(checkin.getId());
        entity.setMode(StudySessionEntity.MODE_CHECKIN);
        entity.setQuality(StudySessionEntity.QUALITY_SELF_REPORTED);
        return entity;
    }

    @Nested
    @DisplayName("upsertToday：首打与幂等更新")
    class Upsert {

        @Test
        @DisplayName("首打 hours=1.5：落打卡并联动 CHECKIN/SELF_REPORTED 会话 minutes=90")
        void firstCheckinCreatesLinkedSession() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.empty());
            when(checkinRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.findByCheckinId(any())).thenReturn(Optional.empty());

            CheckinResponse response = service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, new BigDecimal("1.5"), "专注", 4, null, null));

            ArgumentCaptor<CheckinEntity> checkinCaptor = ArgumentCaptor.forClass(CheckinEntity.class);
            verify(checkinRepository).saveAndFlush(checkinCaptor.capture());
            CheckinEntity saved = checkinCaptor.getValue();
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getUserId()).isEqualTo(UUID.fromString(OWNER));
            assertThat(saved.getDay()).isEqualTo(LocalDate.now(ZONE));
            assertThat(saved.getHours()).isEqualByComparingTo("1.5");
            assertThat(response.hours()).isEqualByComparingTo("1.5");

            ArgumentCaptor<StudySessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(StudySessionEntity.class);
            verify(sessionRepository).save(sessionCaptor.capture());
            StudySessionEntity linked = sessionCaptor.getValue();
            assertThat(linked.getCheckinId()).isEqualTo(saved.getId());
            assertThat(linked.getMode()).isEqualTo("CHECKIN");
            assertThat(linked.getQuality()).isEqualTo("SELF_REPORTED");
            assertThat(linked.getMinutes()).isEqualTo(90);
            assertThat(linked.getStartAt()).isEqualTo(LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant());
            assertThat(linked.getEndAt()).isEqualTo(linked.getStartAt().plusSeconds(90 * 60));
        }

        @Test
        @DisplayName("hours 非 0.1 步进（1.25）：归一为 1.3 落库，联动会话 minutes 按归一后算=78（评审 A2）")
        void hoursNormalizedToOneDecimal() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.empty());
            when(checkinRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.findByCheckinId(any())).thenReturn(Optional.empty());

            service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, new BigDecimal("1.25"), null, null, null, null));

            ArgumentCaptor<CheckinEntity> checkinCaptor = ArgumentCaptor.forClass(CheckinEntity.class);
            verify(checkinRepository).saveAndFlush(checkinCaptor.capture());
            assertThat(checkinCaptor.getValue().getHours()).isEqualByComparingTo("1.3");

            ArgumentCaptor<StudySessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(StudySessionEntity.class);
            verify(sessionRepository).save(sessionCaptor.capture());
            // 1.3 小时 = 78 分钟（归一前 1.25 会得 75，正是两个真相源对不上的偏差源）
            assertThat(sessionCaptor.getValue().getMinutes()).isEqualTo(78);
        }

        @Test
        @DisplayName("重复打卡：同一天原地更新（同一行），联动会话 minutes 同步为 120")
        void repeatedCheckinUpdatesInPlace() {
            CheckinEntity existing = todayCheckin();
            StudySessionEntity linked = linkedSession(existing);
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.of(existing));
            when(checkinRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.findByCheckinId(existing.getId())).thenReturn(Optional.of(linked));
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, new BigDecimal("2"), null, null, "改备注", null));

            ArgumentCaptor<CheckinEntity> checkinCaptor = ArgumentCaptor.forClass(CheckinEntity.class);
            verify(checkinRepository).saveAndFlush(checkinCaptor.capture());
            assertThat(checkinCaptor.getValue().getId()).isEqualTo(existing.getId());
            assertThat(checkinCaptor.getValue().getHours()).isEqualByComparingTo("2");
            assertThat(checkinCaptor.getValue().getNote()).isEqualTo("改备注");

            ArgumentCaptor<StudySessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(StudySessionEntity.class);
            verify(sessionRepository).save(sessionCaptor.capture());
            assertThat(sessionCaptor.getValue().getId()).isEqualTo(linked.getId());
            assertThat(sessionCaptor.getValue().getMinutes()).isEqualTo(120);
        }

        @Test
        @DisplayName("hours 归 0：收回补录，联动会话一并删除（不留悬空 SELF_REPORTED 时长）")
        void zeroHoursDeletesLinkedSession() {
            CheckinEntity existing = todayCheckin();
            StudySessionEntity linked = linkedSession(existing);
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.of(existing));
            // 生产代码接住 saveAndFlush 的受管返回值再 refresh；桩返回入参模拟该语义
            when(checkinRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.findByCheckinId(existing.getId())).thenReturn(Optional.of(linked));

            service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, BigDecimal.ZERO, null, null, null, null));

            verify(sessionRepository).delete(linked);
            verify(sessionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("upsertToday：入参闸门")
    class Validation {

        @Test
        @DisplayName("方向不可见 → 2100")
        void requiresVisibleDirection() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, new BigDecimal("2"), null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2100));
        }

        @Test
        @DisplayName("hours 越界（25 / 负数）→ 1001，不触库")
        void rejectsOutOfRangeHours() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, new BigDecimal("25"), null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
            assertThatThrownBy(() -> service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, new BigDecimal("-1"), null, null, null, null)))
                .isInstanceOf(BusinessException.class);
            verifyNoInteractions(checkinRepository);
        }

        @Test
        @DisplayName("energy 越界（6）→ 1001")
        void rejectsOutOfRangeEnergy() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, new BigDecimal("2"), null, 6, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
            verifyNoInteractions(checkinRepository);
        }

        @Test
        @DisplayName("mood 超 32 字符 → 1001")
        void rejectsOverlongMood() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.upsertToday(OWNER,
                new UpsertCheckinRequest(DIRECTION_ID, new BigDecimal("2"), "好".repeat(33), null, null, null)))
                .isInstanceOf(BusinessException.class);
            verifyNoInteractions(checkinRepository);
        }
    }

    private CheckinService withEventCollector(List<CheckinLinkedEvent> sink) {
        return new CheckinService(checkinRepository, sessionRepository,
            directions, new StudyMapperImpl(), entityManager,
            event -> {
                if (event instanceof CheckinLinkedEvent linked) {
                    sink.add(linked);
                }
            });
    }

    @Nested
    @DisplayName("打卡联动事件（plan-module-adr §决策 3）")
    class LinkedEvent {

        @Test
        @DisplayName("首建且 hours>0 → 发布 CheckinLinkedEvent（分钟 = 归一后 hours×60）")
        void firstInsertPublishesEventOnce() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.empty());
            when(checkinRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.findByCheckinId(any())).thenReturn(Optional.empty());
            List<CheckinLinkedEvent> published = new ArrayList<>();

            withEventCollector(published).upsertToday(OWNER, new UpsertCheckinRequest(DIRECTION_ID,
                new BigDecimal("1.5"), null, null, null, null));

            assertThat(published).hasSize(1);
            CheckinLinkedEvent event = published.get(0);
            assertThat(event.minutes()).isEqualTo(90);
            assertThat(event.directionId()).isEqualTo(UUID.fromString(DIRECTION_ID));
            assertThat(event.userId()).isEqualTo(UUID.fromString(OWNER));
        }

        @Test
        @DisplayName("当日重打（非首建）→ 不补发事件，差额重算被 ADR 否决")
        void repeatedCheckinPublishesNothing() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.of(todayCheckin()));
            when(checkinRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionRepository.findByCheckinId(any())).thenReturn(Optional.of(linkedSession(todayCheckin())));
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            List<CheckinLinkedEvent> published = new ArrayList<>();

            withEventCollector(published).upsertToday(OWNER, new UpsertCheckinRequest(DIRECTION_ID,
                new BigDecimal("3"), null, null, null, null));

            assertThat(published).isEmpty();
        }

        @Test
        @DisplayName("首建但 hours=0 → 不发事件（零时长无可累计）")
        void zeroHoursFirstInsertPublishesNothing() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.empty());
            when(checkinRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            List<CheckinLinkedEvent> published = new ArrayList<>();

            withEventCollector(published).upsertToday(OWNER, new UpsertCheckinRequest(DIRECTION_ID,
                BigDecimal.ZERO, null, null, null, null));

            assertThat(published).isEmpty();
        }
    }

    @Nested
    @DisplayName("today：当日查询")
    class Today {

        @Test
        @DisplayName("今天还没打过 → null（前端据此切换首次打卡态）")
        void todayReturnsNullWhenAbsent() {
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.empty());

            assertThat(service.today(OWNER)).isNull();
        }

        @Test
        @DisplayName("今天打过 → 返回打卡响应")
        void todayReturnsCheckin() {
            CheckinEntity existing = todayCheckin();
            when(checkinRepository.findByUserIdAndDay(UUID.fromString(OWNER), LocalDate.now(ZONE)))
                .thenReturn(Optional.of(existing));

            CheckinResponse response = service.today(OWNER);

            assertThat(response.id()).isEqualTo(existing.getId().toString());
        }
    }
}
