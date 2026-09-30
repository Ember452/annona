-- V14__resume.sql — P1b-08：简历上传与异步 AI 分析（resume 表 + scene CHECK 扩 RESUME）
-- 依据：docs/specs/2026-09-29-interview-session-adr.md 相邻；简历形状借 🅖 modules/resume，
-- 两处按 annona 改：① 单表内嵌 analysis JSONB（🅖 拆 resume + resume_analysis 两张，本仓一场
-- 分析一行、无多版本历史需求，合并更简，符合 Simplicity First）；② (user_id, file_hash) 幂等
-- （复用 knowledge 的 hash-dedup 机制，重复上传零 token）。
-- token_usage.scene 增 'RESUME'：简历分析的模型用量归属（对齐 V12 加 KB_INGEST 的做法）。

CREATE TABLE resume (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    storage_key      VARCHAR(512) NOT NULL,
    file_size        INTEGER NOT NULL,
    content_type     VARCHAR(128) NULL,
    file_hash        VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    analysis         JSONB NULL,
    error            VARCHAR(500) NULL,
    analyzer_version VARCHAR(32) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_resume_user_hash UNIQUE (user_id, file_hash),
    CONSTRAINT chk_resume_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED'))
);
COMMENT ON TABLE resume IS '简历上传与异步分析（P1b-08）：uq_resume_user_hash 是同用户重复上传的幂等兜底；状态机 PENDING→PROCESSING→DONE/FAILED（条件 UPDATE fencing，复用 knowledge 先例）';
COMMENT ON COLUMN resume.id IS '应用侧 UUID.randomUUID()，无 @GeneratedValue（全仓约定）';
COMMENT ON COLUMN resume.analysis IS 'AI 分析结果 JSONB：{summary, strengths[], concerns[], skills[]}；DONE 前为 NULL。可作面试上下文（供后续面试出题/评估参考）';
COMMENT ON COLUMN resume.file_hash IS 'SHA-256(content) 十六进制，(user_id,file_hash) 幂等键（复用 ContentHashes 口径）';
COMMENT ON COLUMN resume.status IS 'PENDING|PROCESSING|DONE|FAILED；PROCESSING 由条件领取保证单实例执行';
COMMENT ON COLUMN resume.analyzer_version IS '分析器版本（复用 RESUME_ANALYZER_VERSION 常量，改 prompt 递增，与 evaluator_version 同思想）';
COMMENT ON COLUMN resume.created_at IS 'DB DEFAULT now()，insertable=false';
COMMENT ON COLUMN resume.updated_at IS '状态转移时推进；恢复调度按它判 stale';
CREATE INDEX idx_resume_user_created ON resume (user_id, created_at DESC);
-- 恢复扫描：只看在途态（partial index，DONE/FAILED 免扫）
CREATE INDEX idx_resume_recovery ON resume (updated_at) WHERE status IN ('PENDING', 'PROCESSING');

ALTER TABLE token_usage DROP CONSTRAINT chk_usage_scene;
ALTER TABLE token_usage ADD CONSTRAINT chk_usage_scene CHECK (scene IN
    ('INTERVIEW', 'QUESTION_GEN', 'QA', 'EVALUATION', 'KB_INGEST', 'RESUME'));
