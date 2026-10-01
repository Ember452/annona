package io.annona.bootstrap;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * demo 数据合成（P1c-08）：{@code annona.demo.enabled=true} 时注入 6 周历史，让可解释面板
 * 一开面就有真实决策可看（演示/A-B 取证用）。
 *
 * <p>门控（AGENTS §4）：默认关——bean 缺席时 {@code AllGatesOffContextIT} 等不受影响；
 * 由 {@code --annona.demo.enabled=true}（或 demo profile）显式打开。幂等：demo 用户已存在则整体跳过。
 *
 * <p>取舍：原生 SQL 而非各模块实体装配——seed 是<b>合成入口不是业务写路径</b>，跨 7 张表直接
 * 落库比拉通 identity/study/interview/evaluation 五个模块的 Service 依赖面小得多；列名逐一对照
 * V1/V2/V8/V9/V13/V15，schema 变更时本文件是显式改点（demo-only，非生产数据通路）。
 */
@Component
@ConditionalOnProperty(prefix = "annona.demo", name = "enabled", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    private static final String DEMO_EMAIL = "demo@annona.test";
    private static final UUID DEMO_USER = UUID.nameUUIDFromBytes("annona-demo-user".getBytes());
    private static final UUID DIRECTION = UUID.nameUUIDFromBytes("annona-demo-direction".getBytes());
    private static final int WEEKS = 6;

    private final JdbcTemplate jdbc;

    public DemoSeedRunner(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now();
        Boolean exists = jdbc.queryForObject(
            "select exists(select 1 from app_user where id = ?)", Boolean.class, DEMO_USER);
        if (DemoSeedPlan.shouldSkip(Boolean.TRUE.equals(exists))) {
            log.info("demo 数据已存在（用户 {}），跳过 seed", DEMO_EMAIL);
            return;
        }
        seedUserAndDirection();
        int questionCount = seedQuestions();
        seedStudySessions(today);
        seedInterviewHistory(today, questionCount);
        log.info("demo 数据合成完成：用户 {} × 方向，{} 周历史", DEMO_EMAIL, WEEKS);
    }

    private void seedUserAndDirection() {
        jdbc.update("insert into app_user (id, email, password_hash, password_algo, status, role)"
            + " values (?, ?, ?, 'scrypt', 'ACTIVE', 'USER')",
            DEMO_USER, DEMO_EMAIL, "$3$not-a-real-hash-demo-only");
        jdbc.update("insert into direction (id, key, name, origin, status, user_id)"
            + " values (?, ?, ?, 'USER_CUSTOM', 'ACTIVE', ?)",
            DIRECTION, "demo-java-concurrency", "Java 并发（演示）", DEMO_USER);
    }

    private int seedQuestions() {
        int[] difficulties = {2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 3, 4};
        int[] followUps = {2, 1, 2, 0, 3, 1, 2, 1, 0, 2, 1, 3};
        Timestamp now = Timestamp.from(Instant.now());
        for (int i = 0; i < difficulties.length; i++) {
            jdbc.update("insert into qb_question (id, user_id, direction_id, question,"
                    + " key_points, difficulty, follow_ups, sources, status, updated_at)"
                    + " values (?, ?, ?, ?, '[]'::jsonb, ?, '[]'::jsonb, '[]'::jsonb,"
                    + " 'ACTIVE', ?)",
                UUID.nameUUIDFromBytes(("demo-q" + i).getBytes()), DEMO_USER, DIRECTION,
                "演示题 " + (i + 1) + "（Java 并发）", difficulties[i], now);
            // followUps 供组卷展平追问，走独立 update（qb_question 无列默认，直接建完补）
            jdbc.update("update qb_question set follow_ups = CAST(? AS jsonb) where id = ?",
                jsonArrayOf(followUps[i]), UUID.nameUUIDFromBytes(("demo-q" + i).getBytes()));
        }
        return difficulties.length;
    }

    private void seedStudySessions(LocalDate today) {
        List<LocalDate> dates = DemoSeedPlan.studyDates(today, WEEKS);
        String[] qualities = {"VERIFIED", "VERIFIED", "PARTIAL", "VERIFIED", "SELF_REPORTED",
            "VERIFIED", "PARTIAL", "VERIFIED", "SELF_REPORTED", "VERIFIED", "VERIFIED",
            "PARTIAL", "VERIFIED", "SELF_REPORTED", "VERIFIED", "VERIFIED"};
        int idx = 0;
        for (LocalDate d : dates) {
            String quality = qualities[idx % qualities.length];
            Instant start = d.atTime(20, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
            Instant end = start.plusSeconds(45 * 60);
            jdbc.update("insert into study_session (id, user_id, direction_id, mode, start_at,"
                    + " end_at, minutes, quality) values (?, ?, ?, 'POMODORO', ?, ?, ?, ?)",
                UUID.randomUUID(), DEMO_USER, DIRECTION, Timestamp.from(start), Timestamp.from(end),
                45, quality);
            idx++;
        }
    }

    /** 造 4 场历史面试：低分（触发 WEAK）+ 最早一场间隔足够久（触发 FORGETTING）。 */
    private void seedInterviewHistory(LocalDate today, int questionCount) {
        List<LocalDate> dates = DemoSeedPlan.interviewDates(today, WEEKS, 4);
        int[] compositeScores = {42, 50, 55, 48};   // 低分 → 触发 WEAK；最早场次间隔大 → 触发 FORGETTING
        for (int s = 0; s < dates.size(); s++) {
            UUID sessionId = UUID.nameUUIDFromBytes(("demo-session-" + s).getBytes());
            LocalDate finishedOn = dates.get(s);
            Instant finished = finishedOn.atTime(21, 0)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
            jdbc.update("insert into interview_session (id, user_id, direction_id, status, plan,"
                    + " current_index, total_count, evaluator_version, started_at, finished_at,"
                    + " created_at, updated_at)"
                    + " values (?, ?, ?, 'COMPLETED', '{}'::jsonb, 3, 3, 'v2', ?, ?, ?, ?)",
                sessionId, DEMO_USER, DIRECTION, Timestamp.from(finished.minusSeconds(1800)),
                Timestamp.from(finished), Timestamp.from(finished), Timestamp.from(finished));
            jdbc.update("insert into interview_report (id, session_id, user_id, evaluator_version,"
                    + " status, composite_score, chat_model, evaluator_model, prompt_hash,"
                    + " created_at, updated_at)"
                    + " values (?, ?, ?, 'v2', 'DONE', ?, 'demo-chat', 'demo-eval',"
                    + " ?, ?, ?)",
                UUID.randomUUID(), sessionId, DEMO_USER, (short) compositeScores[s],
                hashOf(finishedOn), Timestamp.from(finished), Timestamp.from(finished));
            // 逐题低分（供 weakestQuestionIds 取到复习题）：选该难度题
            UUID questionId = UUID.nameUUIDFromBytes(
                ("demo-q" + (s % questionCount)).getBytes());
            jdbc.update("insert into interview_evaluation (id, session_id, question_id,"
                    + " follow_up_index, evaluator_version, score, fallback_used, updated_at)"
                    + " values (?, ?, ?, 0, 'v2', ?, false, ?)",
                UUID.randomUUID(), sessionId, questionId, (short) compositeScores[s],
                Timestamp.from(finished));
        }
    }

    /** 追问 JSON 数组：n 个占位追问对象。 */
    private static String jsonArrayOf(int followUps) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < followUps; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"question\":\"演示追问 ").append(i + 1)
                .append("\",\"keyPoints\":[],\"scoringRubric\":null}");
        }
        return sb.append(']').toString();
    }

    /** 稳定 prompt_hash（同场次可复现；换"模型"演示时改这里即可触发 VERSION_BASELINE）。 */
    private static String hashOf(LocalDate day) {
        return "demo-hash-" + day.getDayOfMonth();
    }
}
