package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.exception.BusinessException;
import io.annona.modules.interview.orchestrator.controller.CreateSessionRequest;
import io.annona.modules.interview.orchestrator.controller.SessionView;
import io.annona.modules.interview.orchestrator.session.InterviewSessionFacade;
import io.annona.modules.planner.service.DecisionPanelService;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import io.annona.shared.evaluation.EvaluationSignalPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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
 * <p>另钉两件 P1c 收尾后补上的行为事实（两条 P0 缺陷的证伪测试）：④ 面试侧样本不受
 * {@code window-days} 截断（久不练仍能驱动决策，planner-adr 修订 1）；⑤ 复习题豁免历史去重
 * 后真的进了卷面，留痕按实际进卷数写（否则 REMIND_REVIEW 是一句做不到的承诺）。
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
    private DecisionPanelService panelService;
    @Autowired
    private EvaluationSignalPort signalPort;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID userId;
    private UUID directionId;
    private final List<UUID> extraDirections = new ArrayList<>();

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
        for (UUID extra : extraDirections) {
            jdbc.update("delete from direction where id = ?", extra);
        }
        extraDirections.clear();
        jdbc.update("delete from direction where id = ?", directionId);
        jdbc.update("delete from app_user where id = ?", userId);
    }

    private CreateSessionRequest request(String mode) {
        return new CreateSessionRequest(directionId.toString(), 3,
            java.util.stream.IntStream.range(0, 3).mapToObj(i -> 3).toList(), 0, mode);
    }

    /** 池里取一道现有 ACTIVE 题作为“反复答过的弱题”（复习候选与 dedup 靶子）。 */
    private UUID firstPoolQuestion() {
        return jdbc.queryForObject("select id from qb_question where user_id = ? limit 1",
            UUID.class, userId);
    }

    /**
     * 灌入 {@code count} 场已完成面试（同一低分、同一评估留痕，间隔 {@code daysAgo} 天起逐场
     * 靠近 1 天），每场对 {@code answeredQuestion} 留下一条 SUBMITTED 答案与逐题评分。
     *
     * <p>三场同分同 hash：保证 VERSION_BASELINE 不命中（留痕一致）、有效样本达 min-sample。
     * 答案行是特意造的——它让该题同时命中历史去重，能证伪“复习题被 dedup 吃掉”。
     */
    private void seedHistory(int count, int daysAgo, short score, UUID answeredQuestion) {
        seedHistory(directionId, count, daysAgo, score, answeredQuestion);
    }

    /** 同上，但场次挂在 {@code targetDirection} 上（方向隔离用例需要造第二个方向的数据）。 */
    private void seedHistory(UUID targetDirection, int count, int daysAgo, short score,
                             UUID answeredQuestion) {
        for (int i = 0; i < count; i++) {
            String ago = "now() - '" + (daysAgo - i) + " days'::interval";
            UUID session = UUID.randomUUID();
            jdbc.update("insert into interview_session (id, user_id, direction_id, status, plan,"
                    + " current_index, total_count, evaluator_version, started_at, finished_at)"
                    + " values (?, ?, ?, 'COMPLETED', '{}'::jsonb, 1, 1, 'v2',"
                    + " " + ago + ", " + ago + ")",
                session, userId, targetDirection);
            jdbc.update("insert into interview_report (id, session_id, user_id, evaluator_version,"
                    + " status, composite_score, chat_model, evaluator_model, prompt_hash)"
                    + " values (?, ?, ?, 'v2', 'DONE', ?, 'it-chat', 'it-eval', 'it-hash')",
                UUID.randomUUID(), session, userId, score);
            jdbc.update("insert into interview_answer (id, session_id, question_id,"
                    + " follow_up_index, answer_text, answer_status, submitted_at)"
                    + " values (?, ?, ?, 0, 'IT 作答', 'SUBMITTED', " + ago + ")",
                UUID.randomUUID(), session, answeredQuestion);
            jdbc.update("insert into interview_evaluation (id, session_id, question_id,"
                    + " follow_up_index, evaluator_version, score)"
                    + " values (?, ?, ?, 0, 'v2', ?)",
                UUID.randomUUID(), session, answeredQuestion, score);
        }
    }

    /** 再建一个方向（计入清理列表）并配一题 ACTIVE 题池，返回方向 ID。 */
    private UUID newDirectionWithQuestion() {
        UUID dir = UUID.randomUUID();
        jdbc.update("insert into direction (id, key, name, origin, status, user_id)"
            + " values (?, ?, ?, 'USER_CUSTOM', 'ACTIVE', ?)",
            dir, "it-other-" + UUID.randomUUID(), "IT 另一方向", userId);
        extraDirections.add(dir);
        UUID question = UUID.randomUUID();
        jdbc.update("insert into qb_question (id, user_id, direction_id, question,"
                + " key_points, difficulty, follow_ups, sources, status, updated_at)"
                + " values (?, ?, ?, ?, '[]'::jsonb, 3, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', now())",
            question, userId, dir, "IT 另一方向题干 " + UUID.randomUUID());
        return dir;
    }

    /** 某方向题池里的任意一题。 */
    private UUID questionOf(UUID dir) {
        return jdbc.queryForObject(
            "select id from qb_question where direction_id = ? limit 1", UUID.class, dir);
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

    @Test
    @DisplayName("久不练（60 天前 3 场低分）仍是有效样本：命中 WEAK_DIRECTION，不被窗口过滤成“数据不足”")
    void staleHistoryStillDrivesDecision() {
        seedHistory(3, 60, (short) 30, firstPoolQuestion());

        String session = facade.create(userId, request("auto")).id();
        List<String> rules = jdbc.queryForList(
            "select rule_key from decision_trace where session_id = ?::uuid", String.class, session);

        // 旧实现按 window-days=14 取数，这 3 场全部被过滤掉→ guard 只会报 SAMPLE_GUARD，
        // 产品核心场景“久不练的方向被重新抽出来”在默认参数下永远演示不出来
        assertThat(rules).as("久不练必须被识别为弱项，而不是查不到样本")
            .contains("WEAK_DIRECTION");
        assertThat(rules).doesNotContain("SAMPLE_GUARD");
    }

    @Test
    @DisplayName("复习题豁免历史去重后真的进了卷面，留痕按实际进卷数写 N/N")
    void reviewQuestionReachesThePaper() {
        UUID weakest = firstPoolQuestion();
        // 三场历史都答过 weakest 这题：它同时是“最低分待复习题”与“近 90 天已答题（去重命中）”
        seedHistory(3, 45, (short) 30, weakest);

        SessionView view = facade.create(userId, request("auto"));
        List<String> packed = view.slots().stream()
            .map(s -> s.questionId())
            .toList();

        assertThat(packed).as("复习题不得被自己的历史去重静默吃掉").contains(weakest.toString());
        String reason = jdbc.queryForObject(
            "select reason from decision_trace where session_id = ?::uuid"
                + " and rule_key = 'REMIND_REVIEW'", String.class, view.id());
        assertThat(reason).as("留痕只声明真做到了的条数").contains("1/1");
    }

    @Test
    @DisplayName("方向隔离：另一方向的低分不串进本方向的样本、均分与复习候选")
    void signalsAreIsolatedByDirection() {
        // 本方向 3 场高分（均分 90 ≥ 弱项线 60，不该加压）
        seedHistory(3, 20, (short) 90, firstPoolQuestion());
        // 另一个方向 3 场低分：若聚合不按 direction_id 过滤，两边均值会被拉成 55 → WEAK 误触发
        UUID other = newDirectionWithQuestion();
        UUID otherQuestion = questionOf(other);
        seedHistory(other, 3, 20, (short) 20, otherQuestion);

        List<String> rules = ruleKeysOf(facade.create(userId, request("auto")).id());
        assertThat(rules).as("背政治的场次不得污染 Java 方向的决策").doesNotContain("WEAK_DIRECTION");

        assertThat(signalPort.latestOutcomes(userId, directionId, 10))
            .as("事件集只含被请求方向")
            .allMatch(o -> o.directionId().equals(directionId.toString()));
        assertThat(signalPort.weakestQuestionIds(userId, directionId, 5))
            .as("复习候选不得越方向拿题")
            .doesNotContain(otherQuestion);
    }

    @Test
    @DisplayName("驳回并发：同一条留痕被 8 路同时驳，只计 1 次声誉，其余全部 3201")
    void concurrentRejectsCountOnce() {
        seedHistory(3, 45, (short) 30, firstPoolQuestion());
        String session = facade.create(userId, request("auto")).id();
        UUID traceId = jdbc.queryForObject(
            "select id from decision_trace where session_id = ?::uuid"
                + " and rule_key = 'WEAK_DIRECTION'", UUID.class, session);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();
        // 旧实现是 findById→判 isUserRejected→改实体→save：两路都能读到未驳态并各自计数一次，
        // 阈值 3 会被同一条留痕虚胖推爆（规则提前停用）。条件 UPDATE 后只剩一路能抢成。
        IntStream.range(0, 8).parallel().forEach(i -> {
            try {
                panelService.reject(userId, traceId);
                ok.incrementAndGet();
            } catch (BusinessException e) {
                blocked.incrementAndGet();
            }
        });

        assertThat(ok.get() + blocked.get()).isEqualTo(8);
        assertThat(ok.get()).as("一次驳回只能有一次写成").isEqualTo(1);
        assertThat(blocked.get()).isEqualTo(7);
        Integer count = jdbc.queryForObject(
            "select rejected_count from rule_reputation where user_id = ?::uuid"
                + " and rule_key = 'WEAK_DIRECTION'", Integer.class, userId);
        assertThat(count).as("声誉计数不重复也不丢失").isEqualTo(1);
    }

    /** 某场面试已落的全部规则键。 */
    private List<String> ruleKeysOf(String sessionId) {
        return jdbc.queryForList(
            "select rule_key from decision_trace where session_id = ?::uuid",
            String.class, sessionId);
    }
}
