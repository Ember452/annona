-- V8__questionbank.sql — P1b-02：知识库出题链（qb_question + qb_generation_task）
-- 依据：docs/specs/2026-09-29-skill-questionbank-adr.md §决策 3/5。
-- 形状借 🅖 knowledge_base_questions（主问题+追问同构、difficulty/scoring_rubric），三处按 annona 改：
--   ① 追问/要点/来源用 JSONB（PG 方言，qa citations 同款）；
--   ② 新增 sources 快照列（题目↔分块溯源；无 FK 的快照语义——文档删除后题目仍在，qa citations 先例）；
--   ③ 任务状态独立成表而非挂 direction 行（跨模块写主数据违规），taskId 即 fencing token。

CREATE TABLE qb_question (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    direction_id     UUID NOT NULL REFERENCES direction(id),
    question         TEXT NOT NULL,
    topic_summary    VARCHAR(300) NULL,
    reference_answer TEXT NULL,
    key_points       JSONB NOT NULL DEFAULT '[]'::jsonb,
    scoring_rubric   TEXT NULL,
    difficulty       SMALLINT NOT NULL,
    follow_ups       JSONB NOT NULL DEFAULT '[]'::jsonb,
    sources          JSONB NOT NULL DEFAULT '[]'::jsonb,
    status           VARCHAR(16) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_question_difficulty CHECK (difficulty BETWEEN 1 AND 5),
    CONSTRAINT chk_question_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

COMMENT ON TABLE qb_question IS '知识库出题的题目池（P1b-02）：主问题与追问同构（追问含各自参考答案/要点/rubric）；出题按 (user, direction) 替换 DRAFT、保留 ACTIVE（skill-questionbank-adr §决策 5）';
COMMENT ON COLUMN qb_question.id IS '应用侧 UUID.randomUUID()，无 @GeneratedValue（全仓约定）';
COMMENT ON COLUMN qb_question.user_id IS '出题发起人；内置方向被多用户共享，题目池按用户隔离';
COMMENT ON COLUMN qb_question.direction_id IS '所属方向（direction.id 外键，禁存 key 字符串——direction ADR 修订 2）';
COMMENT ON COLUMN qb_question.question IS '主问题题干';
COMMENT ON COLUMN qb_question.topic_summary IS '题目主题摘要（列表展示用，可空）';
COMMENT ON COLUMN qb_question.reference_answer IS '参考答案全文';
COMMENT ON COLUMN qb_question.key_points IS '关键点 JSON 字符串数组（评分时的命中锚点）';
COMMENT ON COLUMN qb_question.scoring_rubric IS '评分标准（10 分制分档描述，主问题与追问各自携带）';
COMMENT ON COLUMN qb_question.difficulty IS '难度 1..5（决策层需要数值口径做加权与映射，弃上游三级字符串）';
COMMENT ON COLUMN qb_question.follow_ups IS '追问 JSON 数组：[{question, referenceAnswer, keyPoints, scoringRubric}]，与主问题同构';
COMMENT ON COLUMN qb_question.sources IS '出题上下文快照 JSON 数组：[{docId, chunkId, headingPath}]；无 FK 快照语义（qa citations 同款）';
COMMENT ON COLUMN qb_question.status IS 'DRAFT（新生成待策展）| ACTIVE（用户启用，可被组卷抽取）| ARCHIVED；STALE 不设——文档哈希比对推迟（ADR 否决表）';
COMMENT ON COLUMN qb_question.created_at IS 'DB DEFAULT now()，insertable=false';
COMMENT ON COLUMN qb_question.updated_at IS '应用侧推进';

CREATE INDEX idx_question_user_direction_status ON qb_question (user_id, direction_id, status);
-- 容量校验与组卷抽取都按 ACTIVE + difficulty 过滤：partial index 免扫草稿与归档
CREATE INDEX idx_question_direction_difficulty_active ON qb_question (direction_id, difficulty) WHERE status = 'ACTIVE';

CREATE TABLE qb_generation_task (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    direction_id  UUID NOT NULL REFERENCES direction(id),
    status        VARCHAR(16) NOT NULL,
    config        JSONB NOT NULL,
    saved_count   INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    message       VARCHAR(500) NULL,
    error         VARCHAR(500) NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_generation_task_status CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

COMMENT ON TABLE qb_generation_task IS '出题任务状态机：QUEUED→PROCESSING→COMPLETED/FAILED，重试回 QUEUED；taskId 即 fencing token，全部转移要求 id+status 匹配（借 🅖 原子领取/防旧任务覆盖）';
COMMENT ON COLUMN qb_generation_task.id IS '应用侧 UUID；即 Redis Stream 消息与状态转移共用的 fencing token';
COMMENT ON COLUMN qb_generation_task.user_id IS '发起人；在途唯一按 (user_id, direction_id)——内置方向多用户共享，按单列会跨用户互斥（ADR §后续修订）';
COMMENT ON COLUMN qb_generation_task.direction_id IS '出题目标方向';
COMMENT ON COLUMN qb_generation_task.status IS 'QUEUED|PROCESSING|COMPLETED|FAILED；COMPLETED 不可被 FAILED 覆盖';
COMMENT ON COLUMN qb_generation_task.config IS '请求参数快照 {difficulty, questionCount, followUpCount}；目标追问数由前端读回与实际对比';
COMMENT ON COLUMN qb_generation_task.saved_count IS '有效落库题数（answer 口径：题干非空且批内去重后）';
COMMENT ON COLUMN qb_generation_task.skipped_count IS '因重复/空题干被跳过的数量（缺口提示的输入）';
COMMENT ON COLUMN qb_generation_task.message IS '完成态的缺口提示文案（如"已生成 N 题，跳过 M 道重复"）';
COMMENT ON COLUMN qb_generation_task.error IS '失败原因（对外走安全文案，不透传模型原始报错）';
COMMENT ON COLUMN qb_generation_task.created_at IS 'DB DEFAULT now()，insertable=false';
COMMENT ON COLUMN qb_generation_task.updated_at IS '状态转移时推进；恢复调度按它判 stale';

-- 在途唯一：(user, direction) 同一时刻至多一个 QUEUED/PROCESSING
CREATE UNIQUE INDEX uq_generation_task_inflight
    ON qb_generation_task (user_id, direction_id) WHERE status IN ('QUEUED', 'PROCESSING');
-- 恢复调度扫描：按状态 + updated_at 找 stale（借上游双阈值）
CREATE INDEX idx_generation_task_recovery ON qb_generation_task (status, updated_at);
