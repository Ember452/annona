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
            "'EVALUATION'", "'KB_INGEST'", "'RESUME'");
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

    @Test
    @DisplayName("V14 简历幂等键与状态 CHECK 就位（同用户重复上传不双行）")
    void resumeConstraintsExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'uq_resume_user_hash'",
            Integer.class)).as("(user_id,file_hash) 幂等键").isEqualTo(1);
        String statusDef = jdbc.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'chk_resume_status'",
            String.class);
        assertThat(statusDef).contains("'PENDING'", "'PROCESSING'", "'DONE'", "'FAILED'");
    }

    @Test
    @DisplayName("V19–V21：语音轮 seq 唯一就位，报告 session_id 外键已解除、user_id 级联仍保留")
    void voiceTurnAndPolymorphicReportShape() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'uq_voice_message_seq'",
            Integer.class)).as("会话内轮次唯一的 DB 级兜底（seq 竞态不双写）").isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'chk_report_session_type'",
            String.class)).contains("'INTERVIEW'", "'VOICE'");
        // V20 扩 scene 到 VOICE：有人重建旧版 CHECK 时，计量写入只在真库上炸（V12 同型坑）
        assertThat(jdbc.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'chk_usage_scene'",
            String.class)).contains("'VOICE'");
        // V21 多态化（voice-adr 修订 1 §2）：VOICE 行的宿主是 voice_session，两外键必须不在——
        // 残留则语音报告插行直接撞 FK（就是本批修掉的雷）
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'interview_report_session_id_fkey'",
            Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'interview_evaluation_session_id_fkey'",
            Integer.class)).isZero();
        // user_id 外键与级联是账号删除清理报告的唯一兜底，不得一起被解除
        assertThat(jdbc.queryForObject(
            "select count(*) from pg_constraint where conname = 'interview_report_user_id_fkey'"
                + " and confdeltype = 'c'",
            Integer.class)).as("interview_report.user_id 的 ON DELETE CASCADE 仍在").isEqualTo(1);
    }
}
