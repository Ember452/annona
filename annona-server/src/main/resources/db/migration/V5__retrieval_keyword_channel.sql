-- V5__retrieval_keyword_channel.sql — P1a-07 关键词通道（分词列 + 生成列 tsv + 两个 GIN）+ P1a-09 评测记录表
-- 依据：docs/specs/2026-09-28-retrieval-hybrid-adr.md §决策 1
--       docs/specs/2026-09-25-chinese-keyword-search-adr.md 修订 2026-09-28 第 1/5/6 条
-- 约束：与 V1/V2/V4 相同——PG 方言（TIMESTAMPTZ / JSONB / pgvector）、snake_case、不加反引号、
--       业务表含 user_id 且 (user_id, …) 复合索引；外键列必有索引

-- ========== 1. pg_trgm：关键词通道的兜底匹配（创建不容错） ==========
-- 刻意不套 V1 那个 `EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE` 的 DO 块：
-- 吞掉权限错误后，本文件第 3 节的 gin (content gin_trgm_ops) 会抛一个与真实原因无关的
-- 语法错。扩展缺失应由 FlywayExtensionGuard 预检给可执行提示（REQUIRED_EXTENSIONS 已含
-- pg_trgm）。**后果（运维必读）**：pg_trgm 不可用 = 应用拒绝启动，而不是"兜底通道不可用"。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ========== 2. kb_doc_chunk：应用层分词结果与派生索引列 ==========
-- tokens 由 common/search 的 Tokenizer 端口产出（实现在 infrastructure/search），入库的
-- CHUNKING 步骤随 content 一起写；tsv 是派生列，应用侧**永不直写**，也不映射进 JPA 实体
-- （见下方第 4 段注释与 ADR §决策 2）。
ALTER TABLE kb_doc_chunk
    ADD COLUMN tokens TEXT NULL,
    ADD COLUMN tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, tokens)) STORED,
    ADD COLUMN tokenizer_version VARCHAR(32) NULL;

COMMENT ON COLUMN kb_doc_chunk.tokens IS '应用层中文分词结果（空格连接的 token 串）；单一真相源——换分词词典只重写本列，tsv 由生成列自动重算（keyword ADR 修订第 1 条）。NULL = V5 之前入库的老文档，关键词通道天然不命中，由文档行的"重建"入口补（不建回填机器）';
COMMENT ON COLUMN kb_doc_chunk.tsv IS '关键词通道索引列：由 to_tsvector(''simple'', tokens) STORED 派生，配置固定 simple 以避免 PG 二次切词；JPA 实体刻意不映射（Hibernate 无 tsvector 类型，映射即 ddl-auto: validate 失败；与 embedding 列同款先例）';
COMMENT ON COLUMN kb_doc_chunk.tokenizer_version IS '分词器/词典版本（如 jieba-1.0.2-v1）；**与 kb_doc.analyzer_version 是两个概念**：换本列只重写 tokens，换 analyzer_version 要重新解析与重切正文（keyword ADR 修订第 2 条）。P1a 只有一个版本，检索不按本列过滤；出现第二个版本时先建重建路径再谈版本过滤（修订第 3 条）';

-- ========== 3. 两个 GIN：主通道与兜底通道各一个 ==========
CREATE INDEX idx_kb_doc_chunk_tsv ON kb_doc_chunk USING gin (tsv);
CREATE INDEX idx_kb_doc_chunk_content_trgm ON kb_doc_chunk USING gin (content gin_trgm_ops);

-- ========== 4. retrieval_eval_run：检索评测的一次运行记录（P1a-09） ==========
-- 定位是**投影**，不是真相源：报告本体在 docs/benchmarks/ 与脚本输出的 JSON 里，本表让
-- "混合 vs 纯向量"的历史数字可查询（与 user_session 的审计投影同思路）。
CREATE TABLE retrieval_eval_run (
    id               UUID          PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    query_set        VARCHAR(128)  NOT NULL,
    label            VARCHAR(128)  NOT NULL,
    mode             VARCHAR(16)   NOT NULL,
    backend          VARCHAR(32)   NOT NULL,
    embedding_provider VARCHAR(32) NOT NULL,
    embedding_model  VARCHAR(128)  NULL,
    top_k            INT           NOT NULL,
    query_count      INT           NOT NULL,
    recall_at_k      NUMERIC(5,4)  NULL,
    mrr_at_k         NUMERIC(5,4)  NULL,
    latency_p50_ms   INT           NULL,
    latency_p95_ms   INT           NULL,
    buckets_json     JSONB         NOT NULL DEFAULT '{}'::jsonb,
    report_path      VARCHAR(500)  NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_retrieval_eval_run_mode CHECK (mode IN ('BOTH', 'SEMANTIC', 'KEYWORD')),
    CONSTRAINT chk_retrieval_eval_run_top_k CHECK (top_k > 0 AND query_count >= 0)
);
COMMENT ON TABLE retrieval_eval_run IS '一次 rag-eval 运行的指标投影（scripts/rag-eval 经 POST /api/retrieval/eval-run 写入）。数字本身可在 docs/benchmarks/ 复现，本表用于跨轮对比';
COMMENT ON COLUMN retrieval_eval_run.embedding_provider IS '本轮向量来自哪个 provider（openai-compatible / fake）。**必填且不可省**：fake 的向量与真模型不同空间，而检索按 kb_doc.embedding_model 过滤，混着看会把"管道跑通"误读成"召回差"（ADR §决策 9）';
COMMENT ON COLUMN retrieval_eval_run.mode IS 'BOTH=混合（tsv 主 + trgm 零参数兜底）/ SEMANTIC=纯向量基线（出口条件③的对照组）/ KEYWORD=只走关键词通道';
COMMENT ON COLUMN retrieval_eval_run.buckets_json IS '按 query 分桶（symbol / phrase / clause）的 recall 与 mrr，用于归因"关键词通道到底靠谁"——不分桶则 trgm 与 tsv 的贡献混在一个数里';
CREATE INDEX idx_retrieval_eval_run_user_created ON retrieval_eval_run (user_id, created_at DESC);

-- ========== 5. 订正 V4 的一列注释（V4 已应用不可改，该列口径以本迁移为准） ==========
-- V4 写的是"embedding 模型 id"，而写入方给的是 {@code EmbeddingProvider.name()}——两者实际是同一个值
-- （OpenAI 兼容实现的 name() 返回配置的模型 id），但名字会让下一个读者误以为是供应商名。
COMMENT ON COLUMN kb_doc.embedding_model IS '生成向量的身份：写入方与检索过滤方均取 EmbeddingProvider.name()——OpenAI 兼容实现下就是配置的模型 id（如 text-embedding-v3），fake 实现下是 "fake"。检索 SQL 必须带 d.status = ''READY'' AND d.embedding_model = <当前 provider.name()>，否则换模型后新旧向量混排、fake 与真模型切换会让命中集静默变空（retrieval-hybrid-adr §决策 4）。已入库文档要换身份：走文档行的"重建"全链路';
