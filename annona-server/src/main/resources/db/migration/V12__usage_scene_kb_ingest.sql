-- V12__usage_scene_kb_ingest.sql — 批 3 T0（embed 计量补票，metering-adr 批 3 修订）
-- token_usage.scene 增 'KB_INGEST'：知识库向量化的 embedding 用量此前无处归属
-- （决策 4 的 embed 债）。既有行不受影响（新增值只放宽 CHECK）；V11 冻结禁改，
-- 约束替换走 drop + add（已应用迁移禁改 = 全仓约定，AGENTS §4）。
-- 口径与 V11 一致：EVALUATION 批 3 评估链起用；QA/QUESTION_GEN/INTERVIEW 批 2 已接。

ALTER TABLE token_usage DROP CONSTRAINT chk_usage_scene;
ALTER TABLE token_usage ADD CONSTRAINT chk_usage_scene CHECK (scene IN
    ('INTERVIEW', 'QUESTION_GEN', 'QA', 'EVALUATION', 'KB_INGEST'));
COMMENT ON COLUMN token_usage.scene IS '业务场景（谁花的钱）；EVALUATION 与 KB_INGEST 批 3 起用（V12）';
