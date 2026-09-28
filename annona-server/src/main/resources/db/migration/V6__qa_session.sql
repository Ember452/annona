-- V6__qa_session.sql — P1a-08 流式问答：qa_session（会话）+ qa_message（消息，含结构化引用）
-- 依据：docs/specs/2026-09-28-qa-streaming-adr.md
-- 约束：与 V1–V5 相同——PG 方言（TIMESTAMPTZ / JSONB）、snake_case、不加反引号、
--       业务表含 user_id 且 (user_id, …) 复合索引；外键列必有索引
-- 命名：沿用全仓 *_session，但与登录态/学习/面试的 session 不同构（表注释显式声明，防语义误读）

-- ========== 1. qa_session：问答会话（仅问答域；V6 不带 direction 列，方向关联等真实需求出现再加） ==========
CREATE TABLE qa_session (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title      VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE qa_session IS '流式问答会话。与 user_session（登录态审计投影）、study_session（一次专注 + quality 分级）、interview_session（一场评估 + finalize 幂等）不同构，不共享不变量——同名是全仓命名一致，不是语义一致';
COMMENT ON COLUMN qa_session.title IS '首问标题：取问题前 20 字（🅢 回退口径，一期不调 LLM 起标题）';
COMMENT ON COLUMN qa_session.updated_at IS '新消息落库时由应用侧推进；会话列表按它倒序（最近活跃在前）';
CREATE INDEX idx_qa_session_user_updated ON qa_session (user_id, updated_at DESC);

-- ========== 2. qa_message：问答消息（USER 提问 / ASSISTANT 流式回答） ==========
CREATE TABLE qa_message (
    id            UUID         PRIMARY KEY,
    session_id    UUID         NOT NULL REFERENCES qa_session (id) ON DELETE CASCADE,
    message_order INT          NOT NULL,
    type          VARCHAR(16)  NOT NULL,
    content       TEXT         NOT NULL DEFAULT '',
    completed     BOOLEAN      NOT NULL DEFAULT TRUE,
    citations     JSONB        NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- order 由应用侧 max+1 生成；同会话并发提问在此兜底报错可重试（ADR §后果与约束）
    CONSTRAINT uq_qa_message_session_order UNIQUE (session_id, message_order),
    CONSTRAINT chk_qa_message_type CHECK (type IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_qa_message_citations CHECK (citations IS NULL OR type = 'ASSISTANT')
);
COMMENT ON TABLE qa_message IS '问答消息：USER 行随提问落库；ASSISTANT 行先落空占位（completed=false），流结束一次性回填（非增量追加，借 🅖）';
COMMENT ON COLUMN qa_message.type IS 'USER 提问 / ASSISTANT 回答；区分引用列归属与历史上下文过滤';
COMMENT ON COLUMN qa_message.content IS '消息正文；ASSISTANT 占位阶段为空串，断线保留已生成的部分内容并保持 completed=false（🅖 同语义）';
COMMENT ON COLUMN qa_message.completed IS 'true = 流正常结束的完整回答；追问组装上下文只取 completed 消息，避免把半截回答当上下文（借 🅖）';
COMMENT ON COLUMN qa_message.citations IS '结构化引用（JSONB 数组：docId/chunkId/chunkIndex/headingPath/snippet/score）；仅 ASSISTANT 行持有（CHECK 兜底）；snippet 截前 300 字符——完整正文回查 kb_doc_chunk，引用面板与可解释留痕够用且不复制大文本；JPA 侧 @JdbcTypeCode(SqlTypes.JSON) 往返由 QaFlowIT 证伪（ADR §决策 4）';
-- session_id 外键索引由 uq_qa_message_session_order 最左前缀覆盖，不重复建
