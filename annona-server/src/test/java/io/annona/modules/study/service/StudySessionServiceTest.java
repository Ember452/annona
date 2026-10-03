package io.annona.modules.study.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.study.HeartbeatTimeline;
import io.annona.modules.study.dto.ManualSessionRequest;
import io.annona.modules.study.dto.SessionResponse;
import io.annona.modules.study.dto.StartSessionRequest;
import io.annona.modules.study.entity.StudyEventEntity;
import io.annona.modules.study.entity.StudySessionEntity;
import io.annona.modules.study.mapper.StudyMapperImpl;
import io.annona.modules.study.repository.StudyEventRepository;
import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.direction.service.DirectionQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * StudySessionService 的 Mockito 切片：会话编排的业务规则——方向可见性闸门、
 * 心跳归属与终态校验、finish 由服务端时间线判定（前端无时长话语权）、manual 恒 SELF_REPORTED。
 * mapper 用 MapStruct 真实现，repository / HeartbeatStore / DirectionQueryService 全 mock。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("StudySessionService：会话编排与心跳仲裁")
class StudySessionServiceTest {

    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String DIRECTION_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private StudySessionRepository sessionRepository;

    @Mock
    private StudyEventRepository eventRepository;

    @Mock
    private HeartbeatTimeline heartbeats;

    @Mock
    private DirectionQueryService directions;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(sessionRepository, eventRepository,
            heartbeats, directions, new StudyMapperImpl());
    }

    private StudySessionEntity runningSession(Instant startAt) {
        StudySessionEntity entity = new StudySessionEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(UUID.fromString(OWNER));
        entity.setDirectionId(UUID.fromString(DIRECTION_ID));
        entity.setMode(StudySessionEntity.MODE_POMODORO);
        entity.setStartAt(startAt);
        return entity;
    }

    @Nested
    @DisplayName("start：方向可见性与入参闸门")
    class Start {

        @Test
        @DisplayName("方向不可见（不存在/归档/他人）→ 2100")
        void startRequiresVisibleDirection() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.start(OWNER,
                new StartSessionRequest(DIRECTION_ID, 25, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2100));
        }

        @Test
        @DisplayName("plannedMinutes 越界（0 / 241 / null）→ 1001")
        void startRejectsPlannedMinutes() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.start(OWNER,
                new StartSessionRequest(DIRECTION_ID, 0, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
            assertThatThrownBy(() -> service.start(OWNER,
                new StartSessionRequest(DIRECTION_ID, 241, null)))
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.start(OWNER,
                new StartSessionRequest(DIRECTION_ID, null, null)))
                .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("成功 start：POMODORO、无终态字段，并落 START 事件")
        void startPersistsSessionAndStartEvent() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SessionResponse response = service.start(OWNER,
                new StartSessionRequest(DIRECTION_ID, 25, null));

            ArgumentCaptor<StudySessionEntity> sessionCaptor =
                ArgumentCaptor.forClass(StudySessionEntity.class);
            verify(sessionRepository).save(sessionCaptor.capture());
            StudySessionEntity saved = sessionCaptor.getValue();
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getUserId()).isEqualTo(UUID.fromString(OWNER));
            assertThat(saved.getDirectionId()).isEqualTo(UUID.fromString(DIRECTION_ID));
            assertThat(saved.getMode()).isEqualTo("POMODORO");
            assertThat(saved.getEndAt()).isNull();
            assertThat(saved.getMinutes()).isNull();
            assertThat(saved.getQuality()).isNull();

            ArgumentCaptor<StudyEventEntity> eventCaptor =
                ArgumentCaptor.forClass(StudyEventEntity.class);
            verify(eventRepository).save(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getType()).isEqualTo("START");
            assertThat(eventCaptor.getValue().getSessionId()).isEqualTo(saved.getId());

            assertThat(response.mode()).isEqualTo("POMODORO");
            assertThat(response.endAt()).isNull();
        }

        @Test
        @DisplayName("mode=IMMERSIVE 接受并落库；CHECKIN 拒绝（只能由打卡事务创建）")
        void startAcceptsImmersiveAndRejectsCheckin() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.start(OWNER, new StartSessionRequest(DIRECTION_ID, 45, "IMMERSIVE"));
            ArgumentCaptor<StudySessionEntity> captor = ArgumentCaptor.forClass(StudySessionEntity.class);
            verify(sessionRepository).save(captor.capture());
            assertThat(captor.getValue().getMode()).isEqualTo("IMMERSIVE");

            assertThatThrownBy(() -> service.start(OWNER,
                new StartSessionRequest(DIRECTION_ID, 45, "CHECKIN")))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
        }
    }

    @Nested
    @DisplayName("heartbeat：活着的证明，只落 Redis")
    class Heartbeat {

        @Test
        @DisplayName("他人会话心跳 → 2200（不泄露存在性）")
        void heartbeatOfOthersSessionRejected() {
            UUID sessionId = UUID.randomUUID();
            when(sessionRepository.findByIdAndUserId(sessionId, UUID.fromString(OWNER)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.heartbeat(OWNER, sessionId.toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2200));
            verifyNoInteractions(heartbeats);
        }

        @Test
        @DisplayName("已结束会话心跳 → 2201")
        void heartbeatOnFinishedSessionRejected() {
            StudySessionEntity finished = runningSession(Instant.now().minusSeconds(600));
            finished.setEndAt(Instant.now());
            when(sessionRepository.findByIdAndUserId(finished.getId(), UUID.fromString(OWNER)))
                .thenReturn(Optional.of(finished));

            assertThatThrownBy(() -> service.heartbeat(OWNER, finished.getId().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2201));
            verifyNoInteractions(heartbeats);
        }

        @Test
        @DisplayName("运行中会话心跳 → touch Redis，无 DB 写")
        void heartbeatTouchesRedis() {
            StudySessionEntity running = runningSession(Instant.now().minusSeconds(60));
            when(sessionRepository.findByIdAndUserId(running.getId(), UUID.fromString(OWNER)))
                .thenReturn(Optional.of(running));

            service.heartbeat(OWNER, running.getId().toString());

            verify(heartbeats).touch(eq(running.getId()), any());
            verifyNoInteractions(eventRepository);
        }

        @Test
        @DisplayName("非法 UUID 会话 id → 1001（parseUuid 兑底，不查库）")
        void heartbeatRejectsMalformedUuid() {
            assertThatThrownBy(() -> service.heartbeat(OWNER, "not-a-uuid"))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
            verifyNoInteractions(sessionRepository, heartbeats);
        }
    }

    @Nested
    @DisplayName("finish：服务端时间线权威判定")
    class Finish {

        @Test
        @DisplayName("二次 finish 幂等：已终态直接返回，不再读时间线/落事件")
        void secondFinishIsIdempotent() {
            StudySessionEntity finished = runningSession(Instant.now().minusSeconds(600));
            finished.setEndAt(Instant.now());
            finished.setMinutes(9);
            finished.setQuality(StudySessionEntity.QUALITY_VERIFIED);
            when(sessionRepository.findByIdAndUserId(finished.getId(), UUID.fromString(OWNER)))
                .thenReturn(Optional.of(finished));

            SessionResponse response = service.finish(OWNER, finished.getId().toString(), false);

            assertThat(response.quality()).isEqualTo("VERIFIED");
            assertThat(response.minutes()).isEqualTo(9);
            verifyNoInteractions(heartbeats, eventRepository);
        }

        @Test
        @DisplayName("正常完成：按服务端时间线判 VERIFIED minutes=2，落 FINISH 事件并 evict")
        void finishGradesFromServerTimeline() {
            Instant startAt = Instant.now().minusSeconds(300);
            StudySessionEntity running = runningSession(startAt);
            UUID sessionId = running.getId();
            // 时间线：首段 60s（补 15s）→ 120s 丢 → 60s 计入 → 尾 60s（补 15s）；covered=90s → 2 分钟
            when(sessionRepository.findByIdAndUserId(sessionId, UUID.fromString(OWNER)))
                .thenReturn(Optional.of(running));
            when(heartbeats.timeline(sessionId)).thenReturn(List.of(
                startAt.plusSeconds(60), startAt.plusSeconds(180), startAt.plusSeconds(240)));

            SessionResponse response = service.finish(OWNER, sessionId.toString(), false);

            ArgumentCaptor<StudySessionEntity> captor =
                ArgumentCaptor.forClass(StudySessionEntity.class);
            verify(sessionRepository).save(captor.capture());
            assertThat(captor.getValue().getQuality()).isEqualTo("VERIFIED");
            assertThat(captor.getValue().getMinutes()).isEqualTo(2);
            assertThat(captor.getValue().getEndAt()).isNotNull();
            assertThat(response.minutes()).isEqualTo(2);

            ArgumentCaptor<StudyEventEntity> eventCaptor =
                ArgumentCaptor.forClass(StudyEventEntity.class);
            verify(eventRepository).save(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getType()).isEqualTo("FINISH");
            verify(heartbeats).evict(sessionId);
        }

        @Test
        @DisplayName("中途放弃：落 INTERRUPT 事件，quality 仍按时间线判定")
        void abandonFallsInterruptEvent() {
            Instant startAt = Instant.now().minusSeconds(300);
            StudySessionEntity running = runningSession(startAt);
            UUID sessionId = running.getId();
            when(sessionRepository.findByIdAndUserId(sessionId, UUID.fromString(OWNER)))
                .thenReturn(Optional.of(running));
            when(heartbeats.timeline(sessionId)).thenReturn(List.of(
                startAt.plusSeconds(60), startAt.plusSeconds(180), startAt.plusSeconds(240)));

            service.finish(OWNER, sessionId.toString(), true);

            ArgumentCaptor<StudyEventEntity> eventCaptor =
                ArgumentCaptor.forClass(StudyEventEntity.class);
            verify(eventRepository).save(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getType()).isEqualTo("INTERRUPT");
        }
    }

    @Nested
    @DisplayName("recordBlur：唯一允许前端上报的事件")
    class RecordBlur {

        @Test
        @DisplayName("上报非 BLUR 类型 → 1001")
        void rejectsNonBlurType() {
            assertThatThrownBy(() -> service.recordBlur(OWNER,
                UUID.randomUUID().toString(), "FINISH"))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
            verifyNoInteractions(eventRepository);
        }

        @Test
        @DisplayName("运行中会话上报 BLUR → 落 BLUR 事件")
        void persistsBlurEvent() {
            StudySessionEntity running = runningSession(Instant.now().minusSeconds(60));
            when(sessionRepository.findByIdAndUserId(running.getId(), UUID.fromString(OWNER)))
                .thenReturn(Optional.of(running));

            service.recordBlur(OWNER, running.getId().toString(), "BLUR");

            ArgumentCaptor<StudyEventEntity> captor =
                ArgumentCaptor.forClass(StudyEventEntity.class);
            verify(eventRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo("BLUR");
            assertThat(captor.getValue().getSessionId()).isEqualTo(running.getId());
        }
    }

    @Nested
    @DisplayName("createManual：手动补录恒 SELF_REPORTED")
    class Manual {

        @Test
        @DisplayName("方向不可见 → 2100")
        void manualRequiresVisibleDirection() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.createManual(OWNER, new ManualSessionRequest(
                DIRECTION_ID, Instant.now().minusSeconds(3600), Instant.now())))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2100));
        }

        @Test
        @DisplayName("成功补录：SELF_REPORTED、minutes=墙钟分钟")
        void manualIsSelfReported() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            Instant startAt = Instant.now().minusSeconds(3600);
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SessionResponse response = service.createManual(OWNER,
                new ManualSessionRequest(DIRECTION_ID, startAt, Instant.now()));

            assertThat(response.quality()).isEqualTo("SELF_REPORTED");
            assertThat(response.minutes()).isEqualTo(60);
            assertThat(response.mode()).isEqualTo("CHECKIN");
        }

        @Test
        @DisplayName("倒置范围（endAt ≤ startAt）→ 2202")
        void manualRejectsInvertedRange() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            Instant now = Instant.now();

            assertThatThrownBy(() -> service.createManual(OWNER,
                new ManualSessionRequest(DIRECTION_ID, now, now.minusSeconds(60))))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2202));
        }

        @Test
        @DisplayName("跨度超 24h → 2202")
        void manualRejectsOver24h() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(true);
            Instant now = Instant.now();

            assertThatThrownBy(() -> service.createManual(OWNER,
                new ManualSessionRequest(DIRECTION_ID, now.minusSeconds(90000), now)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2202));
        }
    }

    @Nested
    @DisplayName("today：Asia/Shanghai 日界")
    class Today {

        @Test
        @DisplayName("拉取起点按上海当日 00:00（非 UTC）")
        void todayUsesShanghaiDayStart() {
            ZoneId zone = ZoneId.of("Asia/Shanghai");
            Instant expectedDayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
            when(sessionRepository.findToday(any(), any())).thenReturn(List.of());

            service.today(OWNER);

            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(sessionRepository).findToday(eq(UUID.fromString(OWNER)), captor.capture());
            assertThat(captor.getValue()).isEqualTo(expectedDayStart);
        }
    }
}
