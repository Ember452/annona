package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.evaluation.entity.InterviewReportEntity;
import io.annona.modules.evaluation.repository.InterviewEvaluationRepository;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import io.annona.modules.interview.orchestrator.controller.CreateSessionRequest;
import io.annona.modules.interview.orchestrator.session.InterviewSessionFacade;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评估链<b>数据层幂等与状态机</b>在真 PG 上的证伪集（CI docker-it 专属）：钉两件 mock 探不到、
 * 只有真库能兑现的行为——① 逐题评估 upsert 重投命中同幂等键<b>不双写</b>（V13 uq +
 * ON CONFLICT，与 V9 uq_answer_slot 同构）；② 报告状态机走 fencing 条件 UPDATE，非法转移影响
 * 0 行。{@code @Transactional} 用例级回滚，兼作"调用点有活动事务"的现场证明（无事务则
 * @Modifying 当场抛，批 2 雷型）。
 *
 * <p>"交卷→评估完成→分数可见"的<b>真模型</b>全链由 T-demo 人工承担（docker 无 chat Key；
 * fake 不产合法评分 JSON），本 IT 只覆盖不依赖 LLM 的数据层保证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@Transactional
@DisplayName("评估链数据层真库集测（幂等 upsert + 报告状态机，P1b-06）")
class EvaluationFlowIT {

    @Autowired
    private InterviewSessionFacade facade;
    @Autowired
    private InterviewEvaluationRepository evaluationRepository;
    @Autowired
    private InterviewReportRepository reportRepository;
    @Autowired
    private DirectionRepository directionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID userId;
    private UUID directionId;
    private UUID sessionId;
    private UUID questionId;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(dataSource);
        userId = UUID.randomUUID();
        jdbc.update("insert into app_user (id, email, password_hash, status, role)"
                + " values (?, ?, 'it-not-a-real-hash', 'ACTIVE', 'USER')",
            userId, "it-eval-" + userId + "@annona.test");
        DirectionEntity direction = new DirectionEntity();
        direction.setId(UUID.randomUUID());
        direction.setUserId(userId);
        direction.setKey("it-eval-" + UUID.randomUUID());
        direction.setName("IT 评估方向");
        direction.setOrigin(DirectionEntity.ORIGIN_USER_CUSTOM);
        direction.setStatus(DirectionEntity.STATUS_ACTIVE);
        directionId = directionRepository.save(direction).getId();

        jdbc.update("insert into qb_question (id, user_id, direction_id, question,"
                + " key_points, difficulty, follow_ups, sources, status, updated_at)"
                + " values (?, ?, ?, 'IT 评估题', '[]'::jsonb, 3, '[]'::jsonb, '[]'::jsonb,"
                + " 'ACTIVE', now())",
            UUID.randomUUID(), userId, directionId);
        questionId = jdbc.queryForObject(
            "select id from qb_question where direction_id = ?", UUID.class, directionId);

        CreateSessionRequest plan = new CreateSessionRequest(directionId.toString(), 1,
            java.util.List.of(3), 0);
        sessionId = UUID.fromString(facade.create(userId, plan).id());
    }

    private int upsert(Short score, boolean fallback) {
        return evaluationRepository.upsert(UUID.randomUUID(), sessionId, questionId,
            (short) 0, "v2", score, "fb", "[]", "[]", fallback, fallback ? "RAW" : null,
            Instant.now());
    }

    @Test
    @DisplayName("逐题 upsert 重投命中同幂等键不双写，第二次覆盖明细")
    void upsertIsIdempotentOnRedelivery() {
        assertThat(upsert((short) 60, false)).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();
        // 第二次不同 id、同 (session,question,followup,version) → 走 DO UPDATE 而非插入
        assertThat(upsert((short) 90, false)).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        var rows = evaluationRepository
            .findBySessionIdAndEvaluatorVersionOrderByQuestionIdAscFollowUpIndexAsc(sessionId, "v2");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getScore()).isEqualTo((short) 90);
    }

    @Test
    @DisplayName("报告状态机 fencing：PENDING→RUNNING 只一次，DONE 需先 RUNNING")
    void reportStateMachineFencing() {
        reportRepository.save(InterviewReportEntity.pending(sessionId, userId, "v2", Instant.now()));
        entityManager.flush();

        assertThat(reportRepository.tryMarkRunning(sessionId, "v2", Instant.now())).isEqualTo(1);
        assertThat(reportRepository.tryMarkRunning(sessionId, "v2", Instant.now()))
            .as("已 RUNNING，二次领取失败").isZero();
        assertThat(reportRepository.markDone(sessionId, "v2", (short) 75,
            "{\"strengths\":[],\"improvements\":[],\"overall\":\"ok\"}",
            "glm", "glm", "hash", Instant.now())).isEqualTo(1);
        assertThat(reportRepository.markFailed(sessionId, "v2", "late", Instant.now()))
            .as("DONE 不可被 FAILED 覆盖").isZero();

        entityManager.clear();
        assertThat(reportRepository.findBySessionIdAndEvaluatorVersion(sessionId, "v2"))
            .get().extracting(InterviewReportEntity::getStatus).isEqualTo("DONE");
    }
}
