-- V9__interview_session.sql — P1b-04/05：面试会话与作答 + 题目向量列
-- 依据：docs/specs/2026-09-29-interview-session-adr.md（含批 2 计划 M3/M6/M7/M8 修订）。
-- 形状借 🅖 InterviewSessionEntity/InterviewAnswerEntity + 🅜 flow 状态机，三处按 annona 改：
--   ① 状态只有 RESUMABLE/COMPLETED/ABANDONED（🅜 的 PREPARE/RUNNING 合并进 RESUMABLE——
--      组卷即开始，无独立备考态）；
--   ② evaluator_version 交卷时写 'v1'（批 2 只留幂等契约，批 3 评估消费；TEXT 非枚举，
--      评分器升版不改表）；
--   ③ 追问定位沿用 ADR 先例 (question_id, follow_up_index)，0 为主问题。

CREATE TABLE interview_session (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    direction_id     UUID NOT NULL REFERENCES direction(id),
    status           VARCHAR(16) NOT NULL,
    plan             JSONB NOT NULL,
    current_index    SMALLINT NOT NULL DEFAULT 0,
    total_count      SMALLINT NOT NULL,
    evaluator_version TEXT NULL,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_session_status CHECK (status IN ('RESUMABLE', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT chk_session_index_bounds CHECK (current_index >= 0 AND current_index <= total_count)
);
COMMENT ON TABLE interview_session IS '面试会话：DB 为冷真值，Redis 快照仅作热路径（interview-session-adr §冷热分层）；状态转移全部走条件 UPDATE（fencing，questionbank 先例）';
COMMENT ON COLUMN interview_session.id IS '应用侧 UUID.randomUUID()，无 @GeneratedValue（全仓约定）';
COMMENT ON COLUMN interview_session.plan IS 'InterviewPlan 快照 JSONB（服务端校验后的定稿，非客户端原文）；批 3 评估消费，快照结构版本由 Service 校验器持有，用量可比性归 token_usage.evaluator_version';
COMMENT ON COLUMN interview_session.evaluator_version IS '交卷时写 ''v1''（批 2 语义），幂等键 = session_id + evaluator_version；真正评分批 3 落地';
COMMENT ON COLUMN interview_session.current_index IS '续面恢复位：0..total_count（等于 total_count 表示全部作答完待交卷）';
COMMENT ON COLUMN interview_session.started_at IS 'DB DEFAULT now()，insertable=false；建会话即计时';
COMMENT ON COLUMN interview_session.finished_at IS 'COMPLETED/ABANDONED 转移时应用侧写入';
CREATE INDEX idx_session_user_direction_status ON interview_session (user_id, direction_id, status);
-- 续面恢复扫描：只看在途会话（partial index，ABANDONED 入口之一见 ADR §状态机）
CREATE INDEX idx_session_resumable ON interview_session (user_id) WHERE status = 'RESUMABLE';

CREATE TABLE interview_answer (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_id     UUID NOT NULL REFERENCES qb_question(id),
    follow_up_index SMALLINT NOT NULL DEFAULT 0,
    answer_text     TEXT NULL,
    answer_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_at    TIMESTAMPTZ NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_answer_slot UNIQUE (session_id, question_id, follow_up_index),
    CONSTRAINT chk_answer_status CHECK (answer_status IN ('PENDING', 'SUBMITTED'))
);
COMMENT ON TABLE interview_answer IS '逐题/逐追问作答；uq_answer_slot 是交卷幂等的 DB 级兜底（重复 finalize 不产生双份记录，interview-session-adr §幂等）';
COMMENT ON COLUMN interview_answer.follow_up_index IS '0=主问题，>=1 为第 n 层追问；定位口径沿用 skill-questionbank-adr 的 (question_id, follow_up_index)';
COMMENT ON COLUMN interview_answer.answer_text IS 'PENDING 占位行为 NULL（建会话时按组卷结果整排占位，作答是条件 UPDATE 而非插入）';
CREATE INDEX idx_answer_session ON interview_answer (session_id);

-- M3：题目向量列。出题落库时 best-effort 嵌入（EmbeddingProvider 未配置/失败 → NULL 不阻塞）；
-- 组卷历史去重的向量判只对非 NULL 行生效，NULL 行降级关键词判。HNSW 与 kb_doc_chunk 同参。
ALTER TABLE qb_question ADD COLUMN embedding vector(1024) NULL;
COMMENT ON COLUMN qb_question.embedding IS '题干向量（1024 冻结，同 retrieval 口径）；JPA 实体刻意不映射（kb_doc_chunk.embedding 先例），写入走原生 SQL ::vector';
CREATE INDEX idx_qb_question_embedding ON qb_question USING hnsw (embedding vector_cosine_ops);
