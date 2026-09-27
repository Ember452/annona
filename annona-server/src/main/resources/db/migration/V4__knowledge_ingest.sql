-- V4__knowledge_ingest.sql — P1a-05 知识库入库：kb_doc（文档主档 + 入库状态机）+ kb_doc_chunk（分块 + 向量）
-- 依据：docs/specs/2026-09-27-knowledge-ingestion-adr.md
-- 约束：与 V1/V2 相同——PG 方言（TIMESTAMPTZ / JSONB / pgvector）、snake_case、不加反引号、
--       业务表含 user_id 且 (user_id, …) 复合索引；外键列必有索引
-- 冻结：V1 预留的 fk_direction_kb_doc 在本迁移兑现（V1__baseline.sql direction.kb_doc_id 列注释）

-- ========== 1. kb_doc：知识库文档主档（元数据 + 入库状态机；不存正文全文） ==========
CREATE TABLE kb_doc (
    id               UUID          PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    direction_id     UUID          NOT NULL REFERENCES direction (id),
    file_hash        VARCHAR(64)   NOT NULL,
    name             VARCHAR(255)  NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_size        BIGINT        NOT NULL,
    content_type     VARCHAR(127)  NOT NULL,
    storage_key      VARCHAR(500)  NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    processed_chunks INT           NOT NULL DEFAULT 0,
    total_chunks     INT           NOT NULL DEFAULT 0,
    attempt_id       VARCHAR(36)   NULL,
    recovery_count   INT           NOT NULL DEFAULT 0,
    error            VARCHAR(500)  NULL,
    analyzer_version VARCHAR(32)   NOT NULL,
    embedding_model  VARCHAR(128)  NULL,
    chunk_count      INT           NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- hash 幂等键按用户隔离：同用户重复上传秒级幂等返回（零 token），跨用户不互窥（ADR §后果）
    CONSTRAINT uq_kb_doc_user_hash UNIQUE (user_id, file_hash),
    -- 状态机六态：只允许条件 UPDATE（attempt_id fencing），禁止无条件 setStatus（ADR §决策 3）
    CONSTRAINT chk_kb_doc_status CHECK (status IN ('PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING', 'READY', 'FAILED')),
    CONSTRAINT chk_kb_doc_progress CHECK (processed_chunks >= 0 AND total_chunks >= 0 AND processed_chunks <= total_chunks)
);
COMMENT ON TABLE kb_doc IS '知识库文档主档：元数据 + 状态机（PENDING→PARSING→CHUNKING→EMBEDDING→READY|FAILED）。正文全文不落库——可由 S3 原件重新解析再生（重建式重嵌的依据，ADR §决策 7）';
COMMENT ON COLUMN kb_doc.direction_id IS '上传时必选方向（direction ADR：业务表方向列一律 direction_id 外键，禁自由文本）';
COMMENT ON COLUMN kb_doc.file_hash IS '文件字节 SHA-256（十六进制）；(user_id, file_hash) 唯一 = hash 幂等键，重复上传直接返回已有文档';
COMMENT ON COLUMN kb_doc.storage_key IS 'S3 对象 key（knowledge/{yyyy/MM/dd}/{uuid8}_{安全文件名}）；删除级联在事务外删对象，失败可按 key 补偿';
COMMENT ON COLUMN kb_doc.status IS 'PENDING 待处理 / PARSING 解析 / CHUNKING 分块 / EMBEDDING 向量化 / READY 就绪 / FAILED 失败；每次迁移条件 UPDATE 且写 attempt_id';
COMMENT ON COLUMN kb_doc.attempt_id IS '执行代次 fencing（UUID，借 🅖 vector_attempt_id）：超时回收后旧消费者写不进终态';
COMMENT ON COLUMN kb_doc.recovery_count IS '自动恢复次数（PENDING 超时补投累加，达 3 转 FAILED）；手动 re-vectorize 清零';
COMMENT ON COLUMN kb_doc.error IS 'FAILED 时的可读原因（面向用户展示，不存堆栈）';
COMMENT ON COLUMN kb_doc.analyzer_version IS '分块算法版本（Chunker.VERSION）；升级后按文档重嵌入（重建式），只重分块不重解析';
COMMENT ON COLUMN kb_doc.embedding_model IS '生成向量所用的 embedding 模型 id；检索端只服务与当前配置一致的 READY 文档（换模型不污染向量空间，ADR §决策 6）';
CREATE INDEX idx_kb_doc_user_created ON kb_doc (user_id, created_at DESC);
CREATE INDEX idx_kb_doc_direction ON kb_doc (direction_id);
-- 恢复调度扫描路径：只扫在途状态（借 🅖 idx_kb_vector_status_updated 的部分索引思路）
CREATE INDEX idx_kb_doc_recovery ON kb_doc (status, updated_at)
    WHERE status IN ('PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING');

-- ========== 2. kb_doc_chunk：检索的最小单位（正文 + 源偏移 + 标题路径 + 向量） ==========
CREATE TABLE kb_doc_chunk (
    id           UUID          PRIMARY KEY,
    doc_id       UUID          NOT NULL REFERENCES kb_doc (id) ON DELETE CASCADE,
    chunk_index  INT           NOT NULL,
    heading_path VARCHAR(512)  NOT NULL DEFAULT '',
    char_start   INT           NOT NULL,
    char_end     INT           NOT NULL,
    content      TEXT          NOT NULL,
    content_hash VARCHAR(64)   NOT NULL,
    embedding    vector(1024)  NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_kb_doc_chunk_doc_index UNIQUE (doc_id, chunk_index),
    CONSTRAINT chk_kb_doc_chunk_bounds CHECK (char_start >= 0 AND char_start < char_end)
);
COMMENT ON TABLE kb_doc_chunk IS '分块（Chunker 输出落库）：P1a-07 检索读、P1a-08 引用跳转（char_start/char_end 定位清洗后原文）、分块预览展示';
COMMENT ON COLUMN kb_doc_chunk.heading_path IS '标题路径（"章 > 节 > 小节"）；无标题文档为空串';
COMMENT ON COLUMN kb_doc_chunk.content_hash IS '分块正文 SHA-256；重分块时用于差分对比（v1 仅记录）';
COMMENT ON COLUMN kb_doc_chunk.embedding IS 'pgvector 向量，维度 1024 冻结于 DDL（借 🅖 text-embedding-v3 口径；换模型/维度 = 新迁移重建索引，ADR §后果）。JPA 实体刻意不映射本列（validate 忽略未映射列，direction.parent_id 先例），写入走原生 SQL ::vector';
-- doc_id 外键索引由 uq_kb_doc_chunk_doc_index 最左前缀覆盖，不重复建
CREATE INDEX idx_kb_doc_chunk_embedding ON kb_doc_chunk USING hnsw (embedding vector_cosine_ops);

-- ========== 3. 兑现 V1 预留：direction.kb_doc_id → kb_doc.id ==========
ALTER TABLE direction ADD CONSTRAINT fk_direction_kb_doc FOREIGN KEY (kb_doc_id) REFERENCES kb_doc (id);
COMMENT ON CONSTRAINT fk_direction_kb_doc ON direction IS 'V1 预留（V1__baseline.sql direction.kb_doc_id 注释），P1a-05 建 kb_doc 时兑现；归档方向保留绑定，物理删用户级联时不触及（kb_doc.user_id 自身级联 app_user）';
