-- V7__qa_message_miss_reason.sql — P1a-08 外审修复：空命中诊断随消息持久化
-- 依据：docs/specs/2026-09-28-qa-streaming-adr.md §后续修订 1（外审发现 miss reason 只在
--       流式期可见，历史视图无法回答"凭什么没找到"）。additive 加列而非把 citations 包成
--       对象——改 JSONB 形状会破坏已落库行，V6 已进真实环境即冻结（pre-commit 机检口径）。

ALTER TABLE qa_message ADD COLUMN miss_reason VARCHAR(32) NULL;
COMMENT ON COLUMN qa_message.miss_reason IS '检索空命中诊断（RetrievalMissReason 名）：NO_READY_DOC / MODEL_MISMATCH / NO_MATCH；有命中（MATCHED）与 USER 行恒为 NULL。流式期间经 sources 事件透传，本列让历史视图同样可解释（AGENTS §1 可解释性）';
ALTER TABLE qa_message ADD CONSTRAINT chk_qa_message_miss_reason
    CHECK (miss_reason IS NULL OR (type = 'ASSISTANT' AND miss_reason IN ('NO_READY_DOC', 'MODEL_MISMATCH', 'NO_MATCH')));
