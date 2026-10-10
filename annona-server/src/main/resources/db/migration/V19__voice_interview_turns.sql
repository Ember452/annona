-- V19__voice_interview_turns.sql — P3-03/05：语音面试对话轮 + 题目队列快照
-- 依据：docs/specs/2026-10-06-voice-adr.md 修订 1（题库驱动的对话轮模型）。
-- 约束：PG 方言（TIMESTAMPTZ）、snake_case、外键列必有索引、CHECK 命名 chk_* / 唯一键 uq_*。

-- ========== 1. voice_session 扩列：题目队列快照 + 当前进度 ==========
ALTER TABLE voice_session ADD COLUMN question_ids JSONB NOT NULL DEFAULT '[]';
ALTER TABLE voice_session ADD COLUMN current_question_seq INT NOT NULL DEFAULT 0;
COMMENT ON COLUMN voice_session.question_ids IS '开场时从题库 activePool 截取的题目 id 队列快照（保序）；重连后按它恢复队列，不重查题库';
COMMENT ON COLUMN voice_session.current_question_seq IS '当前进行到的题目下标（0 基，等于已完成轮数）；条件 UPDATE 随轮推进';

-- ========== 2. voice_message：会话内的面试官提问 / 候选人作答轮 ==========
CREATE TABLE voice_message (
    id          UUID         PRIMARY KEY,
    session_id  UUID         NOT NULL REFERENCES voice_session (id) ON DELETE CASCADE,
    seq         INT          NOT NULL,
    -- QUESTION = 面试官发言（LLM 过渡 + 题干）；ANSWER = 候选人本轮作答
    role        VARCHAR(8)   NOT NULL,
    question_id UUID         NULL REFERENCES qb_question (id),
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_voice_message_role CHECK (role IN ('QUESTION', 'ANSWER')),
    CONSTRAINT uq_voice_message_seq UNIQUE (session_id, seq)
);
-- 题目子表：问题经 voice_message.question_id 引用 qb_question；不设独立索引的口径同
-- checkin.direction_id（session 内保序访问是唯一读路径）
COMMENT ON TABLE voice_message IS '语音面试对话轮（P3-03）：QUESTION/ANSWER 交替；ANSWER 关联题干来源，评估按 question_id 对齐评分口径';
COMMENT ON COLUMN voice_message.question_id IS 'ANSWER 轮关联的题库题目；QUESTION 轮可空（过渡语/结束语）';
