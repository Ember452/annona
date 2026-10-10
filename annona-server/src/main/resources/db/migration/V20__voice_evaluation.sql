-- V20__voice_evaluation.sql — P3-05：语音会话接入统一评估引擎
-- 依据：docs/specs/2026-10-06-voice-adr.md 修订 1（报告带 session_type，评估经
--       VoiceEvalQueryService 装配语音作答；scene 增 'VOICE' 照 V12/V14/V17 模板 drop+add）。
-- 约束：既有迁移冻结禁改；CHECK 命名 chk_*；枚举扩展 = 迁移 + 代码常量两处同步。

-- ========== 1. token_usage.scene 增 'VOICE'（语音 ASR 秒数 / TTS 字符数借 tokens 列） ==========
ALTER TABLE token_usage DROP CONSTRAINT chk_usage_scene;
ALTER TABLE token_usage ADD CONSTRAINT chk_usage_scene CHECK (scene IN
    ('INTERVIEW', 'QUESTION_GEN', 'QA', 'EVALUATION', 'KB_INGEST', 'RESUME', 'PLAN', 'VOICE'));
COMMENT ON COLUMN token_usage.scene IS '业务场景（谁花的钱）；VOICE（语音面试链路的 ASR/TTS/LLM）见 V20';

-- ========== 2. interview_report.session_type：同一评估引擎承载两种会话 ==========
ALTER TABLE interview_report ADD COLUMN session_type VARCHAR(16) NOT NULL DEFAULT 'INTERVIEW';
ALTER TABLE interview_report ADD CONSTRAINT chk_report_session_type
    CHECK (session_type IN ('INTERVIEW', 'VOICE'));
CREATE INDEX idx_report_user_session_type ON interview_report (user_id, session_type);
COMMENT ON COLUMN interview_report.session_type IS '会话类型：INTERVIEW=文字面试，VOICE=语音面试（P3-05）；作答装配按类型分流，评分口径同源';
