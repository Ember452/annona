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
| trgm-first：先只做 `pg_trgm` 兜底通道，09 数字证后再上 jieba + `tokens` | 看似省一个依赖与一条迁移，实际第一轮 A/B 打的是"trgm-only"而非本 ADR 设计，**跑输无法归因**（是 jieba 不准、tsv 无收益、还是 trgm 本身不够？）；trgm 是通道内的兜底不是替代 |

## 后果与约束

1. 分词结果成为索引内容的一部分 → **更换分词器或词典必须全量重建索引**（`annona reindex --keyword`），且重建期间新旧 `analyzer_version` 共存，检索需按版本过滤。
2. 应用层分词使入库路径变长（CPU 密集）→ 必须走 `CPU 密集线程池`，不得混在 AI-IO 池里。
3. `doc_chunk` 需要 `analyzer_version` 字段，用于灰度与回滚。
4. 评测脚本纳入 CI 可选阶段（不阻断合并，但指标退化必须在 PR 描述中说明）。

## 何时重新评估

- 文档规模达到千万级向量、或实测 PG 关键词通道 Recall 明显成为瓶颈时，把 `EsRetriever` 从"可选"提为托管版默认（自部署仍保持 pgvector）。

## 修订 2026-09-28（P1a-07 落地时）

本次不改本 ADR 的方向（应用层分词 + `simple` + pg_trgm 兜底），只把七条实现形态定下来。上文决策 1/2/3 与后果 1/2/3 中与本节冲突的部分以本节为准。

1. **分词结果写 `kb_doc_chunk.tokens`，`tsv` 由 STORED 生成列派生**（原决策 1 写的是"结果写入 `doc_chunk.tsv`"）。表名也订正为 V4 实际名 `kb_doc` / `kb_doc_chunk`。
   选它：`tokens` 是单一真相源，换词典只需重写 `tokens`（生成列自动重算），且应用不再手写 `tsvector` 字面量；
   否决直写 tsv：要自己拼 PG 的 text-index 存储格式，且词典升级与 tsv 内容靠应用自律，没有不变量；
   代价：生成列表达式写死 `to_tsvector('simple', tokens)` → **分词输出形状被 DDL 冻结**，所以分词器端口不算对外扩展点（见 AGENTS.md §4 判据）。
2. **`tokenizer_version` 与 `kb_doc.analyzer_version` 是两个概念，各存各的**：前者是分词器/词典版本（换它只重写 `tokens`），后者是分块算法版本（换它要重新解析与重切正文）。后果 3 原写"`doc_chunk` 需要 `analyzer_version`"，沿用既有名字会造成同名不同义，故新建列名。
3. **后果 1 的"换分词器 = 全量重建 + 检索按版本过滤"在 P1a 不兑现**：只有一个 `tokenizer_version`，检索不按版本过滤。出现第二个版本时，重建走已有的 `revectorize` 全链路（PARSING→CHUNKING→EMBEDDING 会重写 `tokens`），`annona reindex --keyword` CLI 归部署环境/CI（AGENTS.md §8.2），P1a 不建回填机器。届时才需版本过滤，触发条件记在这里。
4. **后果 2 的"分词必须走 CPU 密集线程池"本期违反，已登记**：分词与 `Chunker.chunk` 同址（入库消费者的 CHUNKING 步骤内联），原因是分词输入就是刚切好的块、无并行收益，换池要多一次线程交接与结果汇总。重新评估触发：真实文档批量入库时 Micrometer 的 `cpu` 与 `ai-io` 池指标显示堵塞。
5. **pg_trgm 兜底：触发条件为零参数规则（tsv 通道命中 0 行才走），谓词用 `content ILIKE '%'||:q||'%'` 而不是相似度运算符 `content % :q`**。两句话各自的理由：
   - 不用"短查询或命中不足时"这种描述：那是一个隐藏阈值，与"P1a 不做相似度阈值"相冲；"0 行才兜底"无需调参且可解释。
   - 不用 `%`：`similarity(a,b) = 2·|T(a)∩T(b)| / (|T(a)|+|T(b)|)`，分母由两侧全部 trigram 数决定；分块正文动辄几十到几百字，而兜底 query 只有几个字符，比值稳定落在 0.05 量级，**永远够不到 `pg_trgm.similarity_threshold` 的默认 0.3**。写成 `%` 就是一个恒不命中但不报错的谓词——恰好是会伪装成"关键词通道没提升"的那种错。`gin_trgm_ops` 同样索引 `ILIKE '%x%'` 的包含匹配，而那才是"专有名词在正文里出现过"的语义（裁判：`RetrievalSchemaIT#trgmFallbackMatchesSubstringNotSimilarity`，两种谓词同表对比断言）。
6. **`pg_trgm` 创建不容错**：不套 V1 那个 `EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE` 的 DO 块——吞掉权限错误后，紧跟的 `CREATE INDEX ... gin (content gin_trgm_ops)` 会报一个与真实原因无关的语法错。改由 `FlywayExtensionGuard.REQUIRED_EXTENSIONS` 预检给可执行提示。**后果**：扩展不可用 = 启动拦住，而不是"兜底通道不可用"；这是硬依赖，部署文档必须写。
7. **决策 3 的"`degraded` 标记进 SPI 契约"推迟**：P1a 的空命中诊断落在 `POST /api/retrieval/query` 的 `diagnostics.reason`（controller 层 DTO），不进对外契约 jar——没有联网兜底消费方之前，`degraded` 是预留空位（§3.2）。
