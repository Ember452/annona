package io.annona.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V2__study_collection.sql 的<b>纯字符串</b>语法约束测试（本机可跑，无 IO 依赖真实数据库）。
 *
 * <p>模式照抄 {@link FlywayBaselineSqlSyntaxTest}：锁定 P1a-04 三张表的静态前提——
 * 表存在、TIMESTAMPTZ 方言、枚举 CHECK、打卡一天一条的唯一约束、P1c 信号聚合命中的复合索引。
 * 真正的迁移执行由 CI 的 {@code @Tag("docker")} 集测覆盖
 * （见 {@link io.annona.integration.StudyFlowIT}）。
 */
@DisplayName("V2__study_collection.sql 结构约束（P1a-04）")
class StudySchemaSqlSyntaxTest {

    private static final String V2_PATH = "/db/migration/V2__study_collection.sql";

    private static String readV2() throws IOException {
        try (InputStream in = StudySchemaSqlSyntaxTest.class.getResourceAsStream(V2_PATH)) {
            assertThat(in).as("V2__study_collection.sql 必须在 classpath: %s", V2_PATH).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("包含 3 张采集表的 CREATE TABLE（checkin 先建，study_session.checkin_id 才有 FK 目标）")
    void createsAllThreeCollectionTables() throws IOException {
        String sql = readV2();
        assertThat(sql)
            .contains("CREATE TABLE checkin")
            .contains("CREATE TABLE study_session")
            .contains("CREATE TABLE study_event");
        assertThat(sql.indexOf("CREATE TABLE checkin"))
            .as("checkin 必须先于 study_session 建立（FK 依赖）")
            .isLessThan(sql.indexOf("CREATE TABLE study_session"));
    }

    @Test
    @DisplayName("所有时间列使用 TIMESTAMPTZ（PG 方言，禁用不带时区的 TIMESTAMP）")
    void timestampsUseTimestamptz() throws IOException {
        String sql = readV2();
        assertThat(sql).contains("TIMESTAMPTZ");
        assertThat(sql).doesNotContainPattern("TIMESTAMP(?!TZ)");
    }

    @Test
    @DisplayName("quality / mode / type 三列带枚举 CHECK（与 ADR §决策 2 / §5.2 事件枚举一致）")
    void enumColumnsHaveChecks() throws IOException {
        String sql = readV2();
        assertThat(sql).contains("chk_study_session_mode");
        assertThat(sql).contains("chk_study_session_quality");
        assertThat(sql).contains("chk_study_event_type");
        assertThat(sql).contains("'VERIFIED', 'PARTIAL', 'SELF_REPORTED'");
        assertThat(sql).contains("'START', 'BLUR', 'FINISH', 'INTERRUPT'");
    }

    @Test
    @DisplayName("打卡一天一条（UNIQUE (user_id, day)），幂等 upsert 的 DB 依据")
    void checkinIsUniquePerUserPerDay() throws IOException {
        String sql = readV2();
        assertThat(sql).containsPattern("(?i)UNIQUE\\s*\\(user_id,\\s*day\\)");
    }

    @Test
    @DisplayName("P1c 信号聚合命中的复合索引 (user_id, direction_id, start_at) 存在")
    void signalAggregationIndexExists() throws IOException {
        String sql = readV2();
        assertThat(sql).contains("idx_study_session_user_dir_start");
        assertThat(sql).containsPattern("(?i)\\(user_id,\\s*direction_id,\\s*start_at\\)");
    }

    @Test
    @DisplayName("业务表含 user_id 且级联删除；session 与 event 以 ON DELETE CASCADE 挂接")
    void foreignKeysCascadeWithUser() throws IOException {
        String sql = readV2();
        assertThat(sql).containsPattern("(?i)REFERENCES app_user \\(id\\) ON DELETE CASCADE");
        assertThat(sql).containsPattern("(?i)REFERENCES study_session \\(id\\) ON DELETE CASCADE");
    }
}
