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
 *   <li>public schema 下 18 张表齐全（V1 基线八张 + V2 采集三张 + V4 知识库两张 + V5 评测一张
 *       + V6 问答两张 + V8 出题两张 + V9 面试会话两张）；</li>
 *   <li>{@code pg_extension} 含 vector、citext 与 pg_trgm；</li>
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
    @DisplayName("public schema 下 18 张业务表齐全（V1–V9 各迁移登记表，含面试会话两张）")
    void allBaselineTablesExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
            String.class);
        // 期望清单必须随每个新迁移同步扩充：containsExactlyInAnyOrder 兼职守卫
        // "没有迁移外的游离表"，漏登记新表会让本测试假红（V2、V4 两次踩过，V6 又踩一次）。
        assertThat(tables)
            .containsExactlyInAnyOrder(
                "app_user", "user_profile", "user_session", "auth_token",
                "login_attempt", "avatar_change", "user_data_request", "direction",
                "checkin", "study_session", "study_event",
                "kb_doc", "kb_doc_chunk", "retrieval_eval_run",
                "qa_session", "qa_message",
                // V8（P1b-02 出题链）：后续迁移新增表在此追加，保持全库清单断言成立
                "qb_question", "qb_generation_task",
                // V9（P1b-04/05 面试会话）：T0 门禁（scripts/ci/check-migration-inventory.py）会守这一行
                "interview_session", "interview_answer");
    }

    @Test
    @DisplayName("vector、citext、pg_trgm 扩展已安装（pg_trgm 由 V5 不容错创建）")
    void requiredExtensionsInstalled() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> exts = jdbc.queryForList(
            "SELECT extname FROM pg_extension WHERE extname IN ('vector', 'citext', 'pg_trgm')",
            String.class);
        assertThat(exts).containsExactlyInAnyOrder("vector", "citext", "pg_trgm");
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
        // 插一条 SKILL_BUILTIN 方向，走默认值，验证 meta_json 与 status 默认生效。
        // key 用 it- 前缀抽象名：P1b-01 起播种器会在启动时占用真实技能 key（uq_direction_owner_key
        // 的 NULL 命名空间），夹具不得与任何内置技能同名，否则随技能清单演进而脆断。
        jdbc.update("INSERT INTO direction (key, name, origin) VALUES (?, ?, ?)",
            "it-v1-shape-fixture", "V1 形状夹具", "SKILL_BUILTIN");
        // 查询用绑定参数而非字面量：gitleaks 的 generic-api-key 规则会把 key='...' 形态误判
        Integer active = jdbc.queryForObject(
            "SELECT COUNT(*) FROM direction WHERE key = ? AND status = 'ACTIVE' "
                + "AND meta_json = '{}'::jsonb",
            Integer.class, "it-v1-shape-fixture");
        assertThat(active).as("status 默认 ACTIVE、meta_json 默认 {}").isEqualTo(1);
    }
}
