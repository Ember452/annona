package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.evaluation.entity.InterviewReportEntity;
import io.annona.modules.evaluation.repository.InterviewEvaluationRepository;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import io.annona.modules.interview.orchestrator.controller.CreateSessionRequest;
import io.annona.modules.interview.orchestrator.session.InterviewSessionFacade;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import java.time.Instant;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 评估链<b>数据层幂等与状态机</b>在真 PG 上的证伪集（CI docker-it 专属）：钉两件 mock 探不到、
 * 只有真库能兑现的行为——① 逐题评估 upsert 重投命中同幂等键<b>不双写</b>（V13 uq +
 * ON CONFLICT，与 V9 uq_answer_slot 同构）；② 报告状态机走 fencing 条件 UPDATE，非法转移影响
 * 0 行。{@code @Modifying} 写经 {@link TransactionTemplate} 自带短事务（消费线程同款，
 * check-modifying-callers 雷型）。夹具按 fixture userId 建、{@code @AfterEach} 手工清
 * （不用类级 {@code @Transactional}——与 {@link InterviewSessionFlowIT} 一致，避免 JPA 写与
 * JdbcTemplate 跨连接不可见导致 FK 夹具失败）。
 *
 * <p>"交卷→评估完成→分数可见"的<b>真模型</b>全链由 T-demo 人工承担（docker 无 chat Key；
 * fake 不产合法评分 JSON），本 IT 只覆盖不依赖 LLM 的数据层保证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
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
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private UUID userId;
    private UUID directionId;
    private UUID sessionId;
    private UUID questionId;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
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
        directionId = directionRepository.saveAndFlush(direction).getId();

        questionId = UUID.randomUUID();
        jdbc.update("insert into qb_question (id, user_id, direction_id, question,"
                + " key_points, difficulty, follow_ups, sources, status, updated_at)"
                + " values (?, ?, ?, 'IT 评估题', '[]'::jsonb, 3, '[]'::jsonb, '[]'::jsonb,"
                + " 'ACTIVE', now())",
            questionId, userId, directionId);
        sessionId = UUID.fromString(facade.create(userId,
            new CreateSessionRequest(directionId.toString(), 1, List.of(3), 0)).id());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from interview_evaluation where session_id in"
            + " (select id from interview_session where user_id = ?)", userId);
        jdbc.update("delete from interview_report where session_id in"
            + " (select id from interview_session where user_id = ?)", userId);
        jdbc.update("delete from interview_answer where session_id in"
            + " (select id from interview_session where user_id = ?)", userId);
        jdbc.update("delete from interview_session where user_id = ?", userId);
        jdbc.update("delete from qb_question where user_id = ?", userId);
        jdbc.update("delete from direction where id = ?", directionId);
        jdbc.update("delete from app_user where id = ?", userId);
    }

    private void upsert(Short score, boolean fallback, String raw) {
        tx.executeWithoutResult(s -> evaluationRepository.upsert(UUID.randomUUID(), sessionId,
            questionId, (short) 0, "v2", score, "fb", "[]", "[]", fallback, raw, Instant.now()));
    }

    @Test
    @DisplayName("逐题 upsert 重投命中同幂等键不双写，第二次覆盖明细")
    void upsertIsIdempotentOnRedelivery() {
        upsert((short) 60, false, null);
        upsert((short) 90, false, null); // 同 (session,question,followup,version) → DO UPDATE 非插入

        List<Short> scores = tx.execute(s -> evaluationRepository
            .findBySessionIdAndEvaluatorVersionOrderByQuestionIdAscFollowUpIndexAsc(sessionId, "v2"))
            .stream().map(io.annona.modules.evaluation.entity.InterviewEvaluationEntity::getScore).toList();
        assertThat(scores).hasSize(1);
        assertThat(scores.get(0)).isEqualTo((short) 90);
    }

    @Test
    @DisplayName("报告状态机 fencing：PENDING→RUNNING 只一次，DONE 需先 RUNNING")
    void reportStateMachineFencing() {
        tx.executeWithoutResult(s -> reportRepository.save(InterviewReportEntity.pending(
            sessionId, userId, "v2", Instant.now())));

        Integer firstClaim = tx.execute(s -> reportRepository.tryMarkRunning(sessionId, "v2", Instant.now()));
        Integer secondClaim = tx.execute(s -> reportRepository.tryMarkRunning(sessionId, "v2", Instant.now()));
        assertThat(firstClaim).isEqualTo(1);
        assertThat(secondClaim).as("已 RUNNING，二次领取失败").isZero();

        Integer done = tx.execute(s -> reportRepository.markDone(sessionId, "v2", (short) 75,
            "{\"strengths\":[],\"improvements\":[],\"overall\":\"ok\"}",
            "glm", "glm", "hash", Instant.now()));
        assertThat(done).isEqualTo(1);
        Integer lateFail = tx.execute(s ->
            reportRepository.markFailed(sessionId, "v2", "late", Instant.now()));
        assertThat(lateFail).as("DONE 不可被 FAILED 覆盖").isZero();

        var found = tx.execute(s -> reportRepository.findBySessionIdAndEvaluatorVersion(sessionId, "v2"));
        assertThat(found).isPresent().get().extracting(InterviewReportEntity::getStatus).isEqualTo("DONE");
    }
}
