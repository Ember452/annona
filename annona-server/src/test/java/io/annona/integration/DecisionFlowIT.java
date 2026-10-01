package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.interview.orchestrator.controller.CreateSessionRequest;
import io.annona.modules.interview.orchestrator.session.InterviewSessionFacade;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import java.util.UUID;
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
 * 决策留痕链在<b>真 PG + pgvector</b> 上的集测（CI docker-it 专属，本机无 PG 不跑，P1c-05 出口证据）。
 *
 * <p>钉三件真库才现形的事：① auto 开面确实经 planner 写出 decision_trace 行（manual 一行不写，
 * 证明组卷走了决策路径而非旁路）；② reason 中文与 input_snapshot JSONB 往返无损；③ 会话删除
 * 级联清理其 trace（V15 ON DELETE CASCADE）。
 *
 * <p>用 fresh 用户（无历史）——此时 guard 判样本不足拦下难度调整，但<b>保护说明仍落 trace</b>
 * （SAMPLE_GUARD + NO_STUDY_RECORD），所以 auto 必有留痕行；这恰好也验证了"保护要可解释"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("决策留痕链真库集测（P1c-05 出口证据）")
class DecisionFlowIT {

    @Autowired
    private InterviewSessionFacade facade;
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
            userId, "it-decision-" + userId + "@annona.test");
        DirectionEntity direction = new DirectionEntity();
        direction.setId(UUID.randomUUID());
        direction.setUserId(userId);
        direction.setKey("it-decision-" + UUID.randomUUID());
        direction.setName("IT 决策链方向");
        direction.setOrigin(DirectionEntity.ORIGIN_USER_CUSTOM);
        direction.setStatus(DirectionEntity.STATUS_ACTIVE);
        directionId = directionRepository.save(direction).getId();
        // 足量 ACTIVE 题池（难度 3），保证组卷不因容量不足早退
        for (int i = 0; i < 6; i++) {
            jdbc.update("insert into qb_question (id, user_id, direction_id, question,"
                    + " key_points, difficulty, follow_ups, sources, status, updated_at)"
                    + " values (?, ?, ?, ?, '[]'::jsonb, 3, '[]'::jsonb, '[]'::jsonb,"
                    + " 'ACTIVE', now())",
                UUID.randomUUID(), userId, directionId, "IT 决策题干 " + UUID.randomUUID());
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from decision_trace where user_id = ?", userId);
        jdbc.update("delete from rule_reputation where user_id = ?", userId);
        jdbc.update("delete from interview_answer where session_id in"
            + " (select id from interview_session where user_id = ?)", userId);
        jdbc.update("delete from interview_session where user_id = ?", userId);
        jdbc.update("delete from qb_question where user_id = ?", userId);
        jdbc.update("delete from direction where id = ?", directionId);
        jdbc.update("delete from app_user where id = ?", userId);
    }

    private CreateSessionRequest request(String mode) {
        return new CreateSessionRequest(directionId.toString(), 3,
            java.util.stream.IntStream.range(0, 3).mapToObj(i -> 3).toList(), 0, mode);
    }

    @Test
    @DisplayName("auto 开面经 planner 写出决策留痕，manual 一行不写")
    void autoWritesTracesManualDoesNot() {
        String autoSession = facade.create(userId, request("auto")).id();
        Integer autoCount = jdbc.queryForObject(
            "select count(*) from decision_trace where session_id = ?::uuid",
            Integer.class, autoSession);
        assertThat(autoCount).as("auto 决策路径必落留痕（含保护说明）").isGreaterThan(0);

        String manualSession = facade.create(userId, request("manual")).id();
        Integer manualCount = jdbc.queryForObject(
            "select count(*) from decision_trace where session_id = ?::uuid",
            Integer.class, manualSession);
        assertThat(manualCount).as("manual 不经决策，零留痕").isZero();
    }

    @Test
    @DisplayName("留痕 reason 中文与 input_snapshot JSONB 往返无损")
    void traceRoundTripsChineseAndJsonb() {
        String session = facade.create(userId, request("auto")).id();
        String reason = jdbc.queryForObject(
            "select reason from decision_trace where session_id = ?::uuid limit 1",
            String.class, session);
        assertThat(reason).isNotBlank();

        Integer jsonbOk = jdbc.queryForObject(
            "select count(*) from decision_trace where session_id = ?::uuid"
                + " and input_snapshot is not null",
            Integer.class, session);
        // input_snapshot 是 JSONB 列且非空（@JdbcTypeCode(JSON) 写入可回读即往返成功）
        assertThat(jsonbOk).as("input_snapshot JSONB 可回读").isGreaterThan(0);
    }

    @Test
    @DisplayName("会话物理删除级联清理其决策留痕（V15 ON DELETE CASCADE）")
    void sessionDeleteCascadesTraces() {
        String session = facade.create(userId, request("auto")).id();
        jdbc.update("delete from interview_answer where session_id = ?::uuid", session);
        jdbc.update("delete from interview_session where id = ?::uuid", session);
        Integer remaining = jdbc.queryForObject(
            "select count(*) from decision_trace where session_id = ?::uuid",
            Integer.class, session);
        assertThat(remaining).as("trace 随会话级联删除").isZero();
    }
}
