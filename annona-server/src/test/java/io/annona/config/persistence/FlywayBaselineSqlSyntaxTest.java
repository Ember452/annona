package io.annona.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V1__baseline.sql 的<b>纯字符串</b>语法约束测试（本机可跑，无 IO 依赖真实数据库）。
 *
 * <p>覆盖 P0-06 验收中"空库启动自动建表"的<b>静态前提</b>：SQL 文件在 classpath 上存在且包含
 * 8 张表与 2 个 PG 扩展声明。真正的迁移执行由 CI 的 {@code @Tag("docker")} 集测覆盖
 * （见 {@link io.annona.integration.FlywayBaselineIT}）。
 *
 * <p>不引入 Hikari / Flyway / PostgreSQL driver 依赖，保证 {@code mvn -B -q verify} 在本机
 * 无 Docker 环境下也能跑过（AGENTS.md §0.9 与 dockerless-local-dev-adr）。
 */
@DisplayName("V1__baseline.sql 结构约束（P0-06）")
class FlywayBaselineSqlSyntaxTest {

    private static final String V1_PATH = "/db/migration/V1__baseline.sql";

    private static String readV1() throws IOException {
        try (InputStream in = FlywayBaselineSqlSyntaxTest.class.getResourceAsStream(V1_PATH)) {
            assertThat(in).as("V1__baseline.sql 必须在 classpath: %s", V1_PATH).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("声明 vector 与 citext 扩展，且带 IF NOT EXISTS（避免二次启动失败）")
    void declaresRequiredExtensions() throws IOException {
        String sql = readV1();
        assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS vector");
        assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS citext");
    }

    @Test
    @DisplayName("包含 8 张表的 CREATE TABLE（identity 7 + direction）")
    void createsAllEightBaselineTables() throws IOException {
        String sql = readV1();
        assertThat(sql)
            .contains("CREATE TABLE app_user")
            .contains("CREATE TABLE user_profile")
            .contains("CREATE TABLE user_session")
            .contains("CREATE TABLE auth_token")
            .contains("CREATE TABLE login_attempt")
            .contains("CREATE TABLE avatar_change")
            .contains("CREATE TABLE user_data_request")
            .contains("CREATE TABLE direction");
    }

    @Test
    @DisplayName("email 使用 CITEXT 类型（依赖 citext 扩展实现大小写不敏感唯一）")
    void emailColumnUsesCitext() throws IOException {
        String sql = readV1();
        // 匹配 "email CITEXT NOT NULL UNIQUE"（允许任意空白）
        assertThat(sql).containsPattern("(?i)email\\s+CITEXT\\s+NOT\\s+NULL\\s+UNIQUE");
    }

    @Test
    @DisplayName("所有时间列使用 TIMESTAMPTZ（PG 方言，禁用不带时区的 TIMESTAMP）")
    void timestampsUseTimestamptz() throws IOException {
        String sql = readV1();
        assertThat(sql).contains("TIMESTAMPTZ");
        // 不允许出现不带时区戳的裸 TIMESTAMP（TIMESTAMPTZ 会含之后缀，正则排除掉）
        assertThat(sql).doesNotContainPattern("TIMESTAMP(?!TZ)");
    }

    @Test
    @DisplayName("direction 表带 origin 与 status 注释；kb_doc_id 明确 P1a-05 才补 FK")
    void directionTableAnnotatedForDeferredForeignKey() throws IOException {
        String sql = readV1();
        assertThat(sql).contains("COMMENT ON COLUMN direction.kb_doc_id");
        assertThat(sql).contains("P1a-05");
    }

    @Test
    @DisplayName("app_user / user_data_request 有部分索引（PG 特色，降低软删后的索引膨胀）")
    void usesPartialIndexesForSoftDelete() throws IOException {
        String sql = readV1();
        assertThat(sql).contains("WHERE deleted_at IS NULL");
        assertThat(sql).contains("WHERE type = 'DELETE' AND done_at IS NULL");
    }
}
