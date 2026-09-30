-- V13__interview_evaluation.sql — P1b-06/07：逐题评估 + 会话级报告（评估链数据模型）
-- 依据：docs/specs/2026-09-30-evaluation-pipeline-adr.md（幂等复合语义、留痕归属、状态机在此定稿）。
-- 两表各管一事：interview_evaluation 是逐题/逐追问的评估明细，interview_report 是会话级
-- 汇总与异步状态（报告页轮询读它，出口①"完成"判据）。形状借 🅖 UnifiedEvaluationService 的
-- 分批评估 + 二次汇总，两处按 annona 改：① 唯一键含 evaluator_version（幂等重放 + 版本可并存）；
-- ② 难度加权与可比性留痕放会话级 report 而非逐题行（一次评估运行 = 一个评估模型/提示版本）。

CREATE TABLE interview_evaluation (
    id                UUID PRIMARY KEY,
    session_id        UUID NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_id       UUID NOT NULL REFERENCES qb_question(id),
    follow_up_index   SMALLINT NOT NULL DEFAULT 0,
    evaluator_version TEXT NOT NULL,
    score             SMALLINT NULL,
    feedback          TEXT NULL,
    strengths         JSONB NOT NULL DEFAULT '[]'::jsonb,
    improvements      JSONB NOT NULL DEFAULT '[]'::jsonb,
    fallback_used     BOOLEAN NOT NULL DEFAULT false,
    raw_response      TEXT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_evaluation_slot_version
        UNIQUE (session_id, question_id, follow_up_index, evaluator_version),
    CONSTRAINT chk_evaluation_score_range CHECK (score IS NULL OR score BETWEEN 0 AND 100)
);
COMMENT ON TABLE interview_evaluation IS '逐题/逐追问评估明细（P1b-06）：uq_evaluation_slot_version 是评估重投幂等的 DB 级兜底（与 V9 uq_answer_slot 同构，重投走 upsert 不双写）';
COMMENT ON COLUMN interview_evaluation.id IS '应用侧 UUID.randomUUID()，无 @GeneratedValue（全仓约定）';
COMMENT ON COLUMN interview_evaluation.follow_up_index IS '0=主问题，>=1 为第 n 层追问；定位口径沿用 (session_id, question_id, follow_up_index)';
COMMENT ON COLUMN interview_evaluation.evaluator_version IS '评分器版本（幂等键的一部分，本批起 ''v2''）；''v1'' 语义冻结为批 2 幂等占位不改';
COMMENT ON COLUMN interview_evaluation.score IS '该题得分 0..100；fallback（降级）时为 NULL——宁缺勿假分，面板按 fallback_used 单独呈现';
COMMENT ON COLUMN interview_evaluation.strengths IS '亮点 JSON 字符串数组';
COMMENT ON COLUMN interview_evaluation.improvements IS '改进点 JSON 字符串数组';
COMMENT ON COLUMN interview_evaluation.fallback_used IS 'true=模型输出无法解析/畸形，落结构化兜底（出口③）；此时 raw_response 保留逐题原文';
COMMENT ON COLUMN interview_evaluation.raw_response IS '降级时保留的模型原始输出（审计与重跑评估质量分析的输入，正常成功路径为 NULL）';
COMMENT ON COLUMN interview_evaluation.created_at IS 'DB DEFAULT now()，insertable=false';
COMMENT ON COLUMN interview_evaluation.updated_at IS 'upsert 命中既有行时应用侧推进';
CREATE INDEX idx_evaluation_session ON interview_evaluation (session_id);

CREATE TABLE interview_report (
    id                UUID PRIMARY KEY,
    session_id        UUID NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    evaluator_version TEXT NOT NULL,
    status            VARCHAR(16) NOT NULL,
    composite_score   SMALLINT NULL,
    summary           JSONB NULL,
    chat_model        VARCHAR(128) NULL,
    evaluator_model   VARCHAR(128) NULL,
    prompt_hash       VARCHAR(64) NULL,
    error             VARCHAR(500) NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_report_session_version UNIQUE (session_id, evaluator_version),
    CONSTRAINT chk_report_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT chk_report_score_range CHECK (composite_score IS NULL OR composite_score BETWEEN 0 AND 100)
);
COMMENT ON TABLE interview_report IS '会话级评估报告与异步状态（P1b-06/07）：报告页轮询本表；uq_report_session_version 兼当评估幂等锚（一 session 一 version 一行）';
COMMENT ON COLUMN interview_report.status IS 'PENDING（交卷后待评估）|RUNNING（消费中）|DONE（分数可见）|FAILED（重试耗尽）；转移全走 fencing 条件 UPDATE';
COMMENT ON COLUMN interview_report.composite_score IS '难度加权总分 0..100（P1b-07，权重见 ComparabilityRules 常量依据）';
COMMENT ON COLUMN interview_report.summary IS '二次汇总正文 JSON：整体 strengths/improvements/结论（逐题明细在 interview_evaluation）';
COMMENT ON COLUMN interview_report.chat_model IS '出题所用 chat 模型（可比性四留痕之一：换出题模型影响内容可比性）';
COMMENT ON COLUMN interview_report.evaluator_model IS '评分所用模型（可比性四留痕之一：换模型趋势断开，出口④）';
COMMENT ON COLUMN interview_report.prompt_hash IS '评估提示模板 SHA-256（可比性四留痕之一：rubric 口径变更可追溯）';
COMMENT ON COLUMN interview_report.evaluator_version IS '评分器版本；与四留痕共同决定可比区间，跨版本趋势在可比性判定处断开';
COMMENT ON COLUMN interview_report.error IS '失败原因（对外走安全文案，不透传模型原始报错）';
COMMENT ON COLUMN interview_report.created_at IS 'DB DEFAULT now()，insertable=false';
COMMENT ON COLUMN interview_report.updated_at IS '状态转移时推进；恢复调度按它判 stale';
CREATE INDEX idx_report_user_created ON interview_report (user_id, created_at DESC);
