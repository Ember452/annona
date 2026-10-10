package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.interview.orchestrator.controller.CreateSessionRequest;
import io.annona.modules.interview.orchestrator.session.InterviewSessionFacade;
import io.annona.modules.interview.orchestrator.session.InterviewSessionStateService;
import io.annona.modules.questionbank.repository.QbQuestionRepository;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 面试会话链在<b>真 PG + pgvector</b> 上的缺陷证伪集（CI docker-it 专属，本机无 PG 不跑）。
 * 三个用例各钉一个只在真库才会现形的行为：
 * ① 作答中断后上下文重建恢复到当前题（缓存/EM 全丢，DB 是唯一真相）；
 * ② 同一用户连续两场无重复题干（dedup SQL 真题库路径，embedding NULL 走关键词兜底）；
 * ③ 并发双交卷只有一场落终态（fencing 条件 UPDATE 的真原子性，mock 探不到）。
 *
 * <p>不用类级 @Transactional：用例 ③ 要跨线程真提交；夹具按 fixture userId 建、
 * AfterEach 手工清（session/answer 级联，题目与方向直删）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("面试会话链真库集测（P1b-04/05 出口证据）")
class InterviewSessionFlowIT {

    @Autowired
    private InterviewSessionFacade facade;

    @Autowired
    private QbQuestionRepository questionRepository;

    @Autowired
    private DirectionRepository directionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private UUID userId;
    private UUID directionId;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(dataSource);
        userId = UUID.randomUUID();
        // FK 链根：direction.user_id / interview_session.user_id / qb_question.user_id 都指向
        // app_user——USER_CUSTOM 夹具必须先造真实用户行（email 带随机后缀避开活跃唯一索引）
        jdbc.update("insert into app_user (id, email, password_hash, status, role)"
            + " values (?, ?, 'it-not-a-real-hash', 'ACTIVE', 'USER')",
            userId, "it-batch2-" + userId + "@annona.test");
        DirectionEntity direction = new DirectionEntity();
        direction.setId(UUID.randomUUID());
        direction.setUserId(userId);
        direction.setKey("it-flow-" + UUID.randomUUID());
        direction.setName("IT 面试链方向");
        direction.setOrigin(DirectionEntity.ORIGIN_USER_CUSTOM);
        direction.setStatus(DirectionEntity.STATUS_ACTIVE);
        directionId = directionRepository.save(direction).getId();
    }

    @AfterEach
    void cleanup() {
        // V21 后 session_id 不再有外键与级联：交卷用例会让评估链写下逐题明细，残留行的
        // question_id 会顶住下面的 qb_question 删除（voice-adr 修订 1 §2 代价 3）
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

    private void insertQuestions(int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("insert into qb_question (id, user_id, direction_id, question,"
                    + " key_points, difficulty, follow_ups, sources, status, updated_at)"
                    + " values (?, ?, ?, ?, '[]'::jsonb, 3, '[]'::jsonb, '[]'::jsonb,"
                    + " 'ACTIVE', now())",
                UUID.randomUUID(), userId, directionId, "IT 题干唯一标识 " + UUID.randomUUID());
        }
    }

    private CreateSessionRequest plan(int totalCount) {
        return new CreateSessionRequest(directionId.toString(), totalCount,
            java.util.stream.IntStream.range(0, totalCount).mapToObj(i -> 3).toList(), 0, "manual");
    }

    @Test
    @DisplayName("①作答中途重建上下文：从 DB 恢复到当前题且已答内容回显")
    void resumeFromDbAfterContextLoss() {
        insertQuestions(6);
        var view = facade.create(userId, plan(4));
        var first = view.slots().get(0);
        var second = view.slots().get(1);
        assertThat(facade.answer(userId, UUID.fromString(view.id()),
            UUID.fromString(first.questionId()), 0, "第一答")).isTrue();
        assertThat(facade.answer(userId, UUID.fromString(view.id()),
            UUID.fromString(second.questionId()), 0, "第二答")).isTrue();

        // 模拟"重启"：一级/二级缓存全丢，仅剩 DB
        entityManager.clear();
        var resumed = facade.get(userId, UUID.fromString(view.id()));

        assertThat(resumed.currentIndex()).isEqualTo(2);
        assertThat(resumed.slots().get(0).answered()).isTrue();
        assertThat(resumed.slots().get(0).answerText()).isEqualTo("第一答");
        assertThat(resumed.slots().get(2).answered()).isFalse();
    }

    @Test
    @DisplayName("②同一用户连续两场面试无重复题干（embedding NULL 的关键词路径）")
    void twoSessionsDoNotRepeatStems() {
        insertQuestions(6);
        var first = facade.create(userId, plan(3));
        for (var slot : first.slots()) {
            facade.answer(userId, UUID.fromString(first.id()),
                UUID.fromString(slot.questionId()), 0, "答");
        }
        facade.finalizeSession(userId, UUID.fromString(first.id()));

        entityManager.clear();
        var second = facade.create(userId, plan(3));

        var firstIds = first.slots().stream().map(s -> s.questionId()).toList();
        assertThat(second.slots()).hasSize(3);
        assertThat(second.slots()).extracting(s -> s.questionId())
            .doesNotContainAnyElementsOf(firstIds);
    }

    @Test
    @DisplayName("③并发双交卷：恰一方成功，作答记录无双份，会话终态唯一")
    void concurrentFinalizeIsIdempotent() throws Exception {
        insertQuestions(4);
        var view = facade.create(userId, plan(2));
        var sessionId = UUID.fromString(view.id());
        facade.answer(userId, sessionId, UUID.fromString(view.slots().get(0).questionId()),
            0, "唯一作答");

        // 不设发令 latch：invokeAll 在双线程池上即真并发（行锁提供 race 窗口）；
        // 即便偶然串行，恰一成功断言也成立——上一版这里 await 一个永不 countDown 的
        // latch，两个 worker 永久阻塞把 docker-it 挂到 2189s+（CI 实炸，教训入注释）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> attempts = List.of(
                () -> attemptFinalize(sessionId),
                () -> attemptFinalize(sessionId));
            List<Future<Integer>> futures = pool.invokeAll(attempts);
            int successes = 0;
            for (var future : futures) {
                successes += future.get() == 0 ? 1 : 0;
            }
            assertThat(successes).isEqualTo(1);   // 恰一个赢者；另一个拿 2702

            Integer answerRows = jdbc.queryForObject(
                "select count(*) from interview_answer where session_id = ?",
                Integer.class, sessionId);
            Integer completed = jdbc.queryForObject(
                "select count(*) from interview_session where id = ? and status = 'COMPLETED'",
                Integer.class, sessionId);
            assertThat(answerRows).isEqualTo(2);  // 无双份：占位数不变
            assertThat(completed).isEqualTo(1);   // 终态唯一
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("search 允许全空过滤（PG 对 null keyword 推断成 bytea 的批 2 CI 实炸回归）")
    void searchAllowsEmptyFilters() {
        insertQuestions(2);
        assertThat(questionRepository.search(userId, directionId, null, null, null)).hasSize(2);
        assertThat(questionRepository.search(userId, directionId, null, null, "唯一标识"))
            .hasSize(2);
    }

    /** 0 = 交卷成功，1 = 拿到 2702（幂等败者）；其余异常直接失败。 */
    private int attemptFinalize(UUID sessionId) {
        try {
            facade.finalizeSession(userId, sessionId);
            return 0;
        } catch (BusinessException e) {
            if (ErrorCode.SESSION_ALREADY_COMPLETED.getCode() == e.getCode()) {
                return 1;
            }
            throw e;
        }
    }

    @Test
    @DisplayName("交卷后再 finalize 断言幂等错误码路径（单线程重放）")
    void repeatedFinalizeReturnsSameTerminal() {
        insertQuestions(2);
        var view = facade.create(userId, plan(1));
        var sessionId = UUID.fromString(view.id());
        assertThat(facade.finalizeSession(userId, sessionId).status()).isEqualTo("COMPLETED");

        assertThatThrownBy(() -> facade.finalizeSession(userId, sessionId))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.SESSION_ALREADY_COMPLETED.getCode()));
        // 重复交卷不产生双份记录：answer 行数 == 占位数
        assertThat(jdbc.queryForObject(
            "select count(*) from interview_answer where session_id = ?", Integer.class,
            sessionId)).isEqualTo(1);
    }
}
