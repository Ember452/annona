package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V9–V11 迁移形状在<b>真 PG</b> 上的元数据断言（CI docker-it 专属）。
 * 只钉 DDL 意图（partial index 谓词、uq/CHECK 存在）——真实拒绝行为与并发语义由
 * {@link InterviewSessionFlowIT} 的用例承担，两边不重复建夹具（app_user 造行属于
 * identity IT 的地盘，这里拉满表链路只为验证约束定义本身，不值）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("V9–V11 DDL 形状真库兑现（P1b 批 2 迁移出口）")
class MigrationShapeIT {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("续面恢复的 partial index 带 RESUMABLE 谓词（不是全表 index 冒充）")
    void resumablePartialIndexShape() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String def = jdbc.queryForObject(
            "select indexdef from pg_indexes where indexname = 'idx_session_resumable'",
            String.class);
        // PG 规范化会给表达式套括号（((status)::text = ...)）——只断言"部分索引三要素"
        // 各自在场：WHERE + 状态列 + 字面量。全表 idx_session_user_direction_status 不含
        // RESUMABLE 字面量，误冒充会被这三条一起拦下
        assertThat(def).containsIgnoringCase("where")
            .containsIgnoringCase("status")
            .contains("'RESUMABLE'");
    }

    @Test
    @DisplayName("交卷幂等的 DB 级兜底 uq_answer_slot 与六用途 CHECK 就位")
    void constraintsExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer uqSlot = jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'uq_answer_slot'", Integer.class);
        Integer purposeCheck = jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'chk_provider_purpose'",
            Integer.class);
        Integer sceneCheck = jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'chk_usage_scene'", Integer.class);
        assertThat(uqSlot).isEqualTo(1);
        assertThat(purposeCheck).as("evaluator 用途随 V10 落定，不留给 V12（KEK ADR 否决表）")
            .isEqualTo(1);
        assertThat(sceneCheck).isEqualTo(1);
        // V12 把 scene CHECK 扩到五值（+KB_INGEST）：约束定义直接断字面量集，
        // 有人重建旧版四值 CHECK 会让本断言红（计量写入撞约束只在真 PG 暴露）
        String sceneDef = jdbc.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'chk_usage_scene'",
            String.class);
        assertThat(sceneDef).contains("'INTERVIEW'", "'QUESTION_GEN'", "'QA'",
            "'EVALUATION'", "'KB_INGEST'");
    }

    @Test
    @DisplayName("V13 评估链幂等键与报告状态 CHECK 就位（评估重投不双写的 DB 级兜底）")
    void evaluationConstraintsExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'uq_evaluation_slot_version'",
            Integer.class))
            .as("逐题评估幂等键（与 uq_answer_slot 同构，重投 upsert 不双写）").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'uq_report_session_version'",
            Integer.class)).isEqualTo(1);
        String statusDef = jdbc.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'chk_report_status'",
            String.class);
        assertThat(statusDef).contains("'PENDING'", "'RUNNING'", "'DONE'", "'FAILED'");
    }
}
