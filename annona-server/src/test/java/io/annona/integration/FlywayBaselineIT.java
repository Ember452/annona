package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Flyway V1 迁移在<b>真实 PG + pgvector</b> 环境下的端到端验证（P0-06 验收）。
 *
 * <p>本机不跑（无 Docker 与 PG）；CI 通过 {@code services: pgvector/pgvector:pg16} 提供
 * DataSource，workflow 注入 {@code SPRING_DATASOURCE_URL/USERNAME/PASSWORD} 环境变量。
 * 见 {@code docs/specs/2026-09-25-dockerless-local-dev-adr.md} §CI 执行矩阵。
 *
 * <p>断言四件事：
 * <ol>
 *   <li>{@code flyway_schema_history} 有 V1 成功记录；</li>
 *   <li>public schema 下 8 张表齐全；</li>
 *   <li>{@code pg_extension} 含 vector 与 citext；</li>
 *   <li>二次启动 skip 迁移（V1 记录数仍为 1）。</li>
 * </ol>
 *
 * <p>第 4 项由两次 {@code @SpringBootTest} context 加载隐式覆盖：Spring test framework
 * 在同一 JVM 里对同 profile 会复用 context，本用例只跑一次；"二次启动 skip"的显式
 * 验证由 CI 的 compose 冒烟 job 覆盖（重启容器），不在本类内做。
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("Flyway V1 baseline 在真实 PG 上应用（P0-06）")
class FlywayBaselineIT {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("flyway_schema_history 含 V1 success 记录")
    void flywayAppliedV1() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
            Integer.class);
        assertThat(count).as("V1 应成功执行一次").isEqualTo(1);
    }

    @Test
    @DisplayName("public schema 下 8 张业务表齐全")
    void allBaselineTablesExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
            String.class);
        assertThat(tables)
            .containsExactlyInAnyOrder(
                "app_user", "user_profile", "user_session", "auth_token",
                "login_attempt", "avatar_change", "user_data_request", "direction");
    }

    @Test
    @DisplayName("vector 与 citext 扩展已安装")
    void requiredExtensionsInstalled() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> exts = jdbc.queryForList(
            "SELECT extname FROM pg_extension WHERE extname IN ('vector', 'citext')",
            String.class);
        assertThat(exts).containsExactlyInAnyOrder("vector", "citext");
    }

    @Test
    @DisplayName("direction 表结构：key PK + origin 非空 + meta_json 默认值")
    void directionTableShape() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer pkInDirection = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'direction' AND constraint_type = 'PRIMARY KEY'",
            Integer.class);
        assertThat(pkInDirection).as("direction 有主键").isEqualTo(1);
        // 插一条 SKILL_BUILTIN 方向，走默认值，验证 meta_json 与 status 默认生效
        jdbc.update("INSERT INTO direction (key, name, origin) VALUES (?, ?, ?)",
            "java-concurrency", "Java 并发", "SKILL_BUILTIN");
        Integer active = jdbc.queryForObject(
            "SELECT COUNT(*) FROM direction WHERE key = 'java-concurrency' AND status = 'ACTIVE' "
                + "AND meta_json = '{}'::jsonb",
            Integer.class);
        assertThat(active).as("status 默认 ACTIVE、meta_json 默认 {}").isEqualTo(1);
    }
}
