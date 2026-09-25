# ADR: 中文关键词检索通道用应用层分词 + `simple` 配置，不引入 PG 分词扩展

- 日期：2026-09-25
- 状态：Accepted
- 相关：[../annona-项目设计文档.md](../annona-项目设计文档.md) §7

## 背景

混合检索需要一条关键词通道（与 pgvector 语义通道经 RRF 融合）。备考资料以中文为主，而 PostgreSQL 内置 `tsvector` parser 对中文基本没有分词能力（按标点切整句，等于不可用）。上游 MockPilot 用 ES，其 Dockerfile 明确写着 IK 插件安装失败时"fallback to standard analyzer"——即中文按单字切，本来就是降级状态。

## 决策

1. **应用层分词**：入库时用中文分词器（jieba 词库系）切词，结果写入 `doc_chunk.tsv`；查询时用**同一分词器**处理 query；`to_tsquery` 配置的 text search config 固定为 `simple`（避免 PG 再切一次）。
2. **兜底通道**：短查询、专有名词、代码符号用 `pg_trgm` 模糊匹配补充（`pg_trgm` 是 PG 官方扩展，`CREATE EXTENSION` 即可，无需编译）。
3. **可插拔**：`Retriever` SPI 下提供 `EsRetriever`，但**默认不部署 ES**；关键词通道的降级行为在 SPI 契约里显式定义（返回 `degraded` 标记，供上层决定是否走联网兜底）。
4. **用数据说话**：`scripts/rag-eval` 跑 Recall@K / MRR，比较「纯向量」vs「向量+关键词」，结果进 `retrieval_eval_run` 表与 `docs/benchmarks/`。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 换用带 zhparser/pg_jieba 的 PG 镜像 | 需要自己维护镜像或依赖第三方镜像，直接破坏"docker compose up 就能跑"；也让用户在标准 PG 上无法自部署 |
| 只用 pgvector 语义检索，放弃关键词通道 | 专有名词（`JVM`、`Redisson`、法条编号、`CAS`）与数字在向量空间里区分度差，实测容易漏召；而备考题目恰恰大量依赖这类精确词 |
| `to_tsvector('english')` + 手工去停用词 | 对中文等同于按标点切，倒排项长度失控，检索质量不可控 |
| 默认启用 ES | 四中间件（PG+Redis+MinIO+ES）显著抬高自部署门槛，上线初期文档量撑不到 ES 的优势区间 |

## 后果与约束

1. 分词结果成为索引内容的一部分 → **更换分词器或词典必须全量重建索引**（`annona reindex --keyword`），且重建期间新旧 `analyzer_version` 共存，检索需按版本过滤。
2. 应用层分词使入库路径变长（CPU 密集）→ 必须走 `CPU 密集线程池`，不得混在 AI-IO 池里。
3. `doc_chunk` 需要 `analyzer_version` 字段，用于灰度与回滚。
4. 评测脚本纳入 CI 可选阶段（不阻断合并，但指标退化必须在 PR 描述中说明）。

## 何时重新评估

- 文档规模达到千万级向量、或实测 PG 关键词通道 Recall 明显成为瓶颈时，把 `EsRetriever` 从"可选"提为托管版默认（自部署仍保持 pgvector）。
