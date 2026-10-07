-- V18__voice_session.sql — P3-01：语音会话（voice-adr §决策 3，独立会话模型）
-- 依据：docs/specs/2026-10-06-voice-adr.md；设计文档 §10（WS 全链路）与 §5.3 voice_session。
-- 约束：PG 方言（TIMESTAMPTZ）、snake_case、业务表含 user_id 且 (user_id, …) 复合索引、
--       外键列必有索引、CHECK 命名 chk_* / 唯一键 uq_* / 索引 idx_*。
-- 隐私红线（ADR §决策 3）：语音原始音频一律不落库、不落对象存储，只存转写文本。

-- ========== 1. voice_session：一路语音面试会话 ==========
CREATE TABLE voice_session (
    id            UUID          PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    direction_id  UUID          NULL REFERENCES direction (id),
    -- CREATED → ACTIVE → (PAUSED ↔ ACTIVE) → FINALIZED / ABANDONED（服务层全条件 UPDATE）
    status        VARCHAR(16)   NOT NULL DEFAULT 'CREATED',
    -- 开场白文本快照（来源：voice 配置的默认开场白，创建时落快照——配置后改不影响已开会话）
    opening       TEXT          NULL,
    -- 本会话实际使用的 ASR/TTS 模型快照（用量归属留痕；provider=none 时为 NULL）
    asr_model     VARCHAR(120)  NULL,
    tts_model     VARCHAR(120)  NULL,
    -- 会话转写聚合（VAD 断句定稿按顺序拼接；音频不持久化的唯一存留物）
    transcript    TEXT          NOT NULL DEFAULT '',
    -- 端到端延迟实测分布（毫秒；P3-06 预算表口径：停止说话→首包音频），关闭会话时回填
    e2e_latency_p50_ms INT      NULL,
    e2e_latency_p95_ms INT      NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finalized_at  TIMESTAMPTZ   NULL,
    CONSTRAINT chk_voice_session_status CHECK (status IN ('CREATED', 'ACTIVE', 'PAUSED', 'FINALIZED', 'ABANDONED')),
    CONSTRAINT chk_voice_session_latency CHECK (
        (e2e_latency_p50_ms IS NULL OR e2e_latency_p50_ms >= 0)
        AND (e2e_latency_p95_ms IS NULL OR e2e_latency_p95_ms >= 0))
);
CREATE INDEX idx_voice_session_user ON voice_session (user_id);
CREATE INDEX idx_voice_session_user_status ON voice_session (user_id, status);
COMMENT ON TABLE voice_session IS '语音面试会话（P3-01）：独立于 interview_session 的对话式会话；音频不持久化，唯一存留物是转写文本与延迟指标';
COMMENT ON COLUMN voice_session.status IS '会话状态机：CREATED/ACTIVE/PAUSED/FINALIZED/ABANDONED；迁移只允许服务层全条件 UPDATE';
COMMENT ON COLUMN voice_session.opening IS '开场白快照：创建时从 voice 配置落定，之后改配置不影响已开会话';
COMMENT ON COLUMN voice_session.asr_model IS '本会话 ASR 模型快照（用量归属）；provider=none 时 NULL（降级手动提交）';
COMMENT ON COLUMN voice_session.tts_model IS '本会话 TTS 模型快照（用量归属）；provider=none 时 NULL（降级纯字幕）';
COMMENT ON COLUMN voice_session.transcript IS 'VAD 断句定稿文本的顺序聚合；partial 草稿绝不入列（voice-adr §决策 1 契约）';
COMMENT ON COLUMN voice_session.e2e_latency_p50_ms IS '端到端延迟 P50（毫秒）：用户停止说话→首包音频；P3-06 唯一对外验收数字的落库位';
COMMENT ON COLUMN voice_session.e2e_latency_p95_ms IS '端到端延迟 P95（毫秒）：口径同 p50';
