package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.common.exception.BusinessException;
import io.annona.common.study.HeartbeatTimeline;
import io.annona.modules.study.dto.ManualSessionRequest;
import io.annona.modules.study.dto.SessionResponse;
import io.annona.modules.study.dto.StartSessionRequest;
import io.annona.modules.study.service.StudySessionService;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * study 采集全链路在真 PG + 真 Redis 上坐实（本机不跑，交 CI docker-it，模板同 DirectionOwnerScopeIT）：
 * 连续心跳判 VERIFIED（minutes=覆盖口径）、40min 心跳缺口判 PARTIAL（minutes=墙钟，验收条场景）、
 * manual 恒 SELF_REPORTED；他人会话心跳/finish 拒绝（2200 不泄露存在性）。
 * 时间轴无法真等 30 分钟：start() 的 insert 在 flush 前不可见，先 flush 再用 JdbcTemplate
 * 回拨 start_at，随后 clear 防 JPA 一级缓存读到旧值；心跳时刻由测试直接指定（touch 入参即时刻）。
 * Redis 时间线不随测试回滚（sessionId 随机不冲突，TTL 2h 兜底）；@Transactional 仅保 DB 回滚。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Transactional
@Tag("docker")
@DisplayName("study 采集全链路：心跳分级、手动补录与 owner 隔离在真实 PG+Redis 上生效")
class StudyFlowIT {

    @Autowired
    private StudySessionService studySessions;

    @Autowired
    private HeartbeatTimeline heartbeats;

    @Autowired
    private DirectionRepository directionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("start → 25min 每 15s 心跳 → finish=VERIFIED 且 minutes=覆盖口径 25；事件 START+FINISH 共两条")
    void verifiedFlow() {
        UUID owner = insertUser();
        UUID directionId = direction(owner);

        SessionResponse started = studySessions.start(owner.toString(),
            new StartSessionRequest(directionId.toString(), 25));
        UUID sessionId = UUID.fromString(started.id());

        Instant startAt = Instant.now().minus(25, ChronoUnit.MINUTES);
        entityManager.flush();
        jdbcTemplate.update("update study_session set start_at = ? where id = ?",
            Timestamp.from(startAt), sessionId);
        entityManager.clear();
        for (int i = 1; i <= 99; i++) {
            heartbeats.touch(sessionId, startAt.plusSeconds(15L * i));
        }

        SessionResponse finished = studySessions.finish(owner.toString(), sessionId.toString(), false);

        assertThat(finished.quality()).isEqualTo("VERIFIED");
        assertThat(finished.minutes()).isEqualTo(25);
        entityManager.flush();
        Integer events = jdbcTemplate.queryForObject(
            "select count(*) from study_event where session_id = ?", Integer.class, sessionId);
        assertThat(events).isEqualTo(2);
    }

    @Test
    @DisplayName("start 后即挂机 40min（时间线仅一跳）→ finish=PARTIAL 且 minutes=墙钟 40（验收条场景）")
    void idleGapGradesPartial() {
        UUID owner = insertUser();
        UUID directionId = direction(owner);

        SessionResponse started = studySessions.start(owner.toString(),
            new StartSessionRequest(directionId.toString(), 25));
        UUID sessionId = UUID.fromString(started.id());

        entityManager.flush();
        jdbcTemplate.update("update study_session set start_at = ? where id = ?",
            Timestamp.from(Instant.now().minus(40, ChronoUnit.MINUTES)), sessionId);
        entityManager.clear();
        heartbeats.touch(sessionId, Instant.now());

        SessionResponse finished = studySessions.finish(owner.toString(), sessionId.toString(), false);

        assertThat(finished.quality()).isEqualTo("PARTIAL");
        assertThat(finished.minutes()).isEqualTo(40);
    }

    @Test
    @DisplayName("manual 补录 1h → SELF_REPORTED、minutes=60、mode=CHECKIN（不依赖心跳）")
    void manualGradesSelfReported() {
        UUID owner = insertUser();
        UUID directionId = direction(owner);
        Instant endAt = Instant.now();

        SessionResponse manual = studySessions.createManual(owner.toString(),
            new ManualSessionRequest(directionId.toString(), endAt.minusSeconds(3600), endAt));

        assertThat(manual.quality()).isEqualTo("SELF_REPORTED");
        assertThat(manual.minutes()).isEqualTo(60);
        assertThat(manual.mode()).isEqualTo("CHECKIN");
    }

    @Test
    @DisplayName("他人会话的心跳与 finish → 2200（不泄露存在性）")
    void rejectsOtherUsersSession() {
        UUID owner = insertUser();
        UUID intruder = insertUser();
        UUID directionId = direction(owner);

        SessionResponse started = studySessions.start(owner.toString(),
            new StartSessionRequest(directionId.toString(), 25));
        String sessionId = started.id();

        assertThatThrownBy(() -> studySessions.heartbeat(intruder.toString(), sessionId))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(2200));
        assertThatThrownBy(() -> studySessions.finish(intruder.toString(), sessionId, false))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(2200));
    }

    @Test
    @DisplayName("start 用他人方向 → 2100（existsVisibleTo 在真库上生效）")
    void startRejectsInvisibleDirection() {
        UUID owner = insertUser();
        UUID stranger = insertUser();
        UUID strangerDirection = direction(stranger);

        assertThatThrownBy(() -> studySessions.start(owner.toString(),
            new StartSessionRequest(strangerDirection.toString(), 25)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(2100));
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into app_user (id, email, password_hash, password_algo, status, role)"
                + " values (?, ?, 'x', 'scrypt', 'ACTIVE', 'USER')",
            id, "it-" + id + "@study.test");
        return id;
    }

    private UUID direction(UUID owner) {
        DirectionEntity entity = new DirectionEntity();
        entity.setId(UUID.randomUUID());
        entity.setKey("study-" + owner);
        entity.setName("自习方向");
        entity.setOrigin(DirectionEntity.ORIGIN_USER_CUSTOM);
        entity.setStatus(DirectionEntity.STATUS_ACTIVE);
        entity.setUserId(owner);
        return directionRepository.saveAndFlush(entity).getId();
    }
}
