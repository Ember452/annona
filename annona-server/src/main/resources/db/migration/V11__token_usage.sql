-- V11__token_usage.sql — P1b-10：模型用量记账
-- 依据：docs/specs/2026-09-29-llmprovider-metering-adr.md（scene/purpose 二维口径在此定稿）。
-- 借 🅢 schema.prisma::TokenUsage 与 🅖 用量表，两处按 annona 改：
--   ① surface/tier（产品入口与模型档位）换 scene/purpose（计费归属与模型用途）——
--      surface 混淆了"从哪进来"，tier 是供应商侧概念；我们要回答的是"哪个场景花了哪个用途的钱"；
--   ② session_id 不设 FK：按 scene 指向不同宿主表（interview_session/qa_session/…），
--      单列多宿主用 FK 要么多外键互斥 CHECK 要么建桥表，聚合读都不如现在一条索引直接。

CREATE TABLE token_usage (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    scene             VARCHAR(24) NOT NULL,
    session_id        UUID NULL,
    provider          VARCHAR(64) NOT NULL,
    model             VARCHAR(128) NOT NULL,
    purpose           VARCHAR(16) NOT NULL,
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    prompt_hash       VARCHAR(64) NULL,
    evaluator_version TEXT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_usage_scene CHECK (scene IN ('INTERVIEW', 'QUESTION_GEN', 'QA', 'EVALUATION'))
);
COMMENT ON TABLE token_usage IS '模型调用用量账（异步写，不进业务事务，llmprovider-metering-adr §记账）。行只增不改——成本与用量的历史真值';
COMMENT ON COLUMN token_usage.user_id IS '归属用户 = 计费主体；托管代持模式下同样是发起方（KEK ADR §决策 6 的记账前提）';
COMMENT ON COLUMN token_usage.scene IS '业务场景（谁花的钱）；EVALUATION 批 3 起用';
COMMENT ON COLUMN token_usage.session_id IS '场景宿主 ID（interview_session/qa_session/…），可空不设 FK；会话钻取走 idx_usage_session';
COMMENT ON COLUMN token_usage.purpose IS '模型用途（chat/embedding/…），与 llm_provider_config.purpose 同枚举域（KEK ADR 五用途 + RERANK，M5）';
COMMENT ON COLUMN token_usage.prompt_hash IS '请求正文 SHA-256（评估可比性留痕，批 3 消费）；不含正文本身，无泄漏面';
COMMENT ON COLUMN token_usage.evaluator_version IS '评估类调用的评估器版本；与面试会话的幂等键同域';
CREATE INDEX idx_usage_user_created ON token_usage (user_id, created_at DESC);
CREATE INDEX idx_usage_session ON token_usage (session_id);
