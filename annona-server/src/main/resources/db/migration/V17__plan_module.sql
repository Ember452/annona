-- V17__plan_module.sql — P2-06：计划与任务两表 + token_usage scene 增 'PLAN'
-- 依据：docs/specs/2026-10-03-plan-module-adr.md（打卡联动语义 / 拆分 reconcile /
--       PLAN scene 计量）。既有迁移冻结禁改；scene 放宽照 V12 模板 drop + add。
-- 约束：PG 方言（TIMESTAMPTZ）、snake_case、业务表含 user_id 且 (user_id, …) 复合索引、
--       外键列必有索引、CHECK 命名 chk_* / 唯一键 uq_* / 索引 idx_*。

-- ========== 1. plan：一份学习计划（MD 全文 + 最近拆分指纹） ==========
CREATE TABLE plan (
    id           UUID          PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    direction_id UUID          NULL REFERENCES direction (id),
    title        VARCHAR(120)  NOT NULL,
    document     TEXT          NOT NULL DEFAULT '',
    -- 最近一次 AI 拆任务时的文档指纹（sha256 hex）：相同则短路不重复花 token；
    -- 文档更新时由服务层置空，下次 split 重新计算
    source_hash  VARCHAR(64)   NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_plan_user ON plan (user_id);
COMMENT ON TABLE plan IS '学习计划：MD 文档为真相源，任务由 AI 拆分或手动追加（P2-06）';
COMMENT ON COLUMN plan.document IS '计划全文（GFM Markdown）；任务拆分与工作室编辑的对象';
COMMENT ON COLUMN plan.source_hash IS '最近一次拆分时的 sha256(document)；NULL=文档已变待重拆';

-- ========== 2. plan_task：计划下的任务（打卡联动的承接单位） ==========
CREATE TABLE plan_task (
    id               UUID          PRIMARY KEY,
    plan_id          UUID          NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    -- 冗余 owner：今日待办跨计划一条查询，不必 join plan（与 plan.user_id 保持一致由服务层保证）
    user_id          UUID          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    direction_id     UUID          NULL REFERENCES direction (id),
    title            VARCHAR(160)  NOT NULL,
    description      VARCHAR(600)  NULL,
    category         VARCHAR(20)   NOT NULL DEFAULT 'study',
    priority         VARCHAR(10)   NOT NULL DEFAULT 'normal',
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    -- 目标分钟：AI 估时或缺省 25；瀑布累计到达即自动 DONE（ADR §决策 3）
    target_minutes   INT           NOT NULL DEFAULT 25,
    progress_minutes INT           NOT NULL DEFAULT 0,
    source           VARCHAR(10)   NOT NULL DEFAULT 'MANUAL',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_plan_task_category CHECK (category IN ('study', 'project', 'review', 'exercise')),
    CONSTRAINT chk_plan_task_priority CHECK (priority IN ('high', 'normal', 'low')),
    CONSTRAINT chk_plan_task_status CHECK (status IN ('PENDING', 'DONE')),
    CONSTRAINT chk_plan_task_source CHECK (source IN ('AI', 'MANUAL')),
    CONSTRAINT chk_plan_task_target CHECK (target_minutes BETWEEN 5 AND 600),
    CONSTRAINT chk_plan_task_progress CHECK (progress_minutes >= 0)
);
CREATE INDEX idx_plan_task_plan ON plan_task (plan_id);
CREATE INDEX idx_plan_task_user_status ON plan_task (user_id, status);
-- 联动瀑布的取数路径：user + direction + PENDING + created_at 升序（ADR §决策 3）
CREATE INDEX idx_plan_task_user_dir_status ON plan_task (user_id, direction_id, status);
COMMENT ON TABLE plan_task IS '计划任务：打卡联动按 direction 瀑布累计分钟，进度达 target 自动完成（P2-06）';
COMMENT ON COLUMN plan_task.target_minutes IS '目标专注分钟（5..600）；联动累计到达即 DONE';
COMMENT ON COLUMN plan_task.progress_minutes IS '打卡联动已累计分钟；只由监听器与 reconcile 维护，无手动入口（ADR §后果）';
COMMENT ON COLUMN plan_task.source IS 'AI=拆分生成 | MANUAL=手动追加';

-- ========== 3. token_usage.scene 增 'PLAN'（V12/V14 模板：drop + add） ==========
ALTER TABLE token_usage DROP CONSTRAINT chk_usage_scene;
ALTER TABLE token_usage ADD CONSTRAINT chk_usage_scene CHECK (scene IN
    ('INTERVIEW', 'QUESTION_GEN', 'QA', 'EVALUATION', 'KB_INGEST', 'RESUME', 'PLAN'));
COMMENT ON COLUMN token_usage.scene IS '业务场景（谁花的钱）；RESUME 见 V14，PLAN（计划拆任务/工作室对话）见 V17';
