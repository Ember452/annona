# ADR: 混合检索用"全含 pgvector 后端 + 生成列关键词通道"，评测按分桶出真数字

- 日期：2026-09-28 · 状态：Accepted
- 相关：[chinese-keyword-search-adr](./2026-09-25-chinese-keyword-search-adr.md)（修订 2026-09-28 在文末）·
  [knowledge-ingestion-adr](./2026-09-27-knowledge-ingestion-adr.md)（V4 表与 `embedding_model` 契约）·
  [storage-single-postgres-adr](./2026-09-25-storage-single-postgres-adr.md) ·
  [dockerless-local-dev-adr](./2026-09-25-dockerless-local-dev-adr.md)
- 对应任务：P1a-07（混合检索）、P1a-09（评测基线）

## 背景

代码里看不出来的那部分约束：

1. **`annona-spi` 还没发布过**：`git ls-remote --tags origin` 为空，`publish-spi.yml` 只在 `v*` tag 上触发。所以本轮给 `RetrievalQuery` 加字段是零成本，而第一个 tag 打出去就变成破坏性变更——这是**有时限的窗口**，不抓住就要么永久冻结要么另建接口。
2. **本机无 Docker**（AGENTS §0.9）：`to_tsvector` 能否进 STORED 生成列、`pg_trgm` 权限、`ts_rank` 排序、GIN 命中——这些只有真 PG 能答。用户 2026-09-28 明确否决"装本机原生 PG 换快反馈"（与 CI 的 `pgvector/pg16` 在 pgvector 小版本、contrib 可用性、collation 三处漂移，"本机过 ≠ CI 过"）。因此 SQL 未知数必须**压进一次 docker-it 推送集中断言**，而不是分两轮撞。
3. **`RetrievalHit.score` 的既有 javadoc 承诺"归一化到 [0,1]"**，而 RRF 的 `Σ1/(K+rank)`（K=60）双通道第一也只有 ≈0.033，永远碰不到 1——两者必须在落地时对齐，否则 08 的引用置信度展示、09 的报告列、SPI 契约是三套含义。
4. **批 1 已把 `embedding_model` 写成契约**（`KbDocRepository` L71 注释 + V4 列注释"检索端只服务与当前配置一致的 READY 文档"），但检索当时还不存在，所以这条契约**没有实现方也没有测试**——fake 与真模型切换会让命中集合静默变空。
5. 产品的非技术方向（法考/CPA 类"用户讲义驱动出题"）与评测语料的形状有关：技术讲义术语密集、标题层级规整，恰好是分块器与分词器的舒适区。

## 决策

1. **V5 建 `pg_trgm`（不容错）+ `kb_doc_chunk` 加 `tokens TEXT` / `tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', tokens)) STORED` / `tokenizer_version VARCHAR(32)` + `GIN(tsv)` + `GIN(content gin_trgm_ops)`，并建 `retrieval_eval_run` 表**。`tokens IS NULL` 时生成列为空 tsvector，该行自然不被关键词通道命中（无回填机器）。
2. **`tsv` 与 `embedding` 一样刻意不映射到 JPA 实体**（Hibernate 无 tsvector 类型，映射即 `ddl-auto: validate` 失败）；`tokens`/`tokenizer_version` 要映射，随 `persistChunks` 的 saveAll 写入。
3. **`Retriever` = 全含后端**：`PgVectorRetriever` 内部完成语义 SQL + 关键词 SQL + trgm 兜底 + RRF；`modules/retrieval/hybrid` 只放与后端无关的 RRF 纯函数（表驱动单测）。`RetrievalQuery` 增 `mode`（`BOTH`/`SEMANTIC`/`KEYWORD`，紧凑构造器默认 `BOTH`），在本窗口内定稿，此后只允许加 default 方法。
4. **两条通道 SQL 都必须带 `d.status = 'READY' AND d.embedding_model = :currentModel`**，并保留 `d.user_id = :uid`。缺 `embedding_model` 谓词属未完成（批 1 契约的兑现方在这里）。
5. **出口归一化 `score = rrf / (2.0 / (K + 1))`** 落进既有 `[0,1]` 契约；javadoc 写明解读口径"双通道均排第一 ≈1.0，仅单通道命中最高 ≈0.5"。不设独立重排步骤——两通道各自在同一条 SQL 里带出排序依据（语义 `1 - (embedding <=> :qvec)`，关键词 `ts_rank`）。
6. **trgm 兜底是零参数规则（tsv 通道命中 0 行才走）且谓词必须是包含匹配** `content ILIKE '%'||:q||'%'`，**不得用相似度运算符 `content % :q`**：长正文 vs 短 query 的 trigram 相似度永远低于默认 0.3 阈值，写错不报错、只会静默把关键词通道变成空集（算术推导与两条谓词的对比断言见 keyword ADR 修订第 5 条）。不引入任何相似度阈值（P1a 不做阈值）。
7. **`POST /api/retrieval/query` 只回 `{docId, chunkId, score}` + `diagnostics{readyDocs, modelMatchedDocs, reason}`**，`reason ∈ {NO_READY_DOC, MODEL_MISMATCH, NO_MATCH}`。正文与偏移由消费方（qa）按 `chunkId` 批量回查 `kb_doc_chunk`；`diagnostics` 留在 controller 层 DTO，不进 SPI。
8. **backend 路由用 `@ConditionalOnProperty(annona.retrieval.backend)`**：`pgvector`（默认）/ `fake`（注册 `FakeRetriever`），未知值启动 fail-fast。
9. **评测必须能归因**：`scripts/rag-eval/corpus/` 除 4-6 篇技术讲义外**必须含 1 篇条款体**（法条/会计准则，公开领域）；`queries.json` 每条带 `bucket ∈ {symbol, phrase, clause}` 与 `relevant_doc_ids` 数组；embedding 来源条件化（`EVAL_EMBEDDING_API_KEY` 存在 → 真模型出决策级数字，缺席 → `provider=fake` 只做管道回归）；报告与指标文档**必须写明本轮 provider**。
10. **jieba 依赖（`com.huaban:jieba-analysis:1.0.2`）按 §0.6 四问登记**：解决"PG 内置 parser 对中文无分词能力"；不用的代价是关键词通道对中文长句等效失效（只剩 trgm 子串匹配）；为什么现有方案不够——zhparser/pg_jieba 要自维护镜像、破坏"compose 就能跑"（见 keyword ADR 否决表）；可移除性——端口在 `common/search`，换实现即换类，生成列表达式与 `tokenizer_version` 不依赖它。风险如实记录：该 artifact 最后发布于 2015-06-17（11 年未更新，冻结型稳定），且传递依赖 `commons-lang3:3.3.1` 需在引入后核查版本竞争。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| trgm-first（先只上兜底通道，09 证后再上 jieba+tsv） | 第一轮 A/B 打的是 trgm-only 而非 ADR 实际设计，跑输无法归因；详见 keyword ADR 否决表 |
| 检索返回体直接带正文与偏移 | 要么膨胀 SPI 契约 jar（每个后端实现方都被迫返大块文本），要么在 07 里猜 08 需要的字段；按 `chunkId` 回查同一张表一次 `IN` 就够 |
| `mode` 用启动配置切换、不用请求参数 | 评测要跑三档就得重启三次（CI 里每轮等健康检查）；且语义通道与关键词通道的 SQL 差异本就在同一实现内 |
| `degraded` 标记进 SPI 契约（keyword ADR 决策 3 原文） | 没有联网兜底消费方之前是预留空位（§3.2）；`diagnostics.reason` 已覆盖"能解释"这件事 |
| RRF 融合下沉为 SQL 窗口函数 | 20 条候选规模下客户端融合的开销可忽略，而下沉后要测的是 PG 的 SQL 而不是融合算法，表驱动单测反而更难写 |
| 存量老文档自动回填 `tokens`（启动扫描 / 差分机器） | 已有 `revectorize` 全链路会重写 tokens，缺的只是入口；开发期无真实用户数据，做机器是投机（给 READY 行加"重建"按钮即可） |
| 本机装原生 PG 验 SQL | 与 CI 镜像三处漂移，"本机过 ≠ CI 过"（用户 2026-09-28 否决，已钉进 AGENTS §8.3） |
| rerank 模型通道立刻上 | 没有 `top4_miss_type` 证据前是猜；改为带触发条件的推迟（见"何时重新评估"） |

## 后果与约束

- V5 一旦在任何真实环境应用即被 checksum 冻结（AGENTS §4），结构变更一律 V6+；`tokenizer_version` 常量升级要新增迁移重写 `tokens`，不许改 V5。
- **P1a 期间不打 `v*` tag**：SPI 形状（`RetrievalQuery.mode`、`RetrievalHit` 字段与 score 口径）必须在 P1a 内定稿，此后只能加 default 方法。
- `rag-eval` job 必须有对象存储 service 且显式 `ANNONA_KNOWLEDGE_INGEST_ENABLED=true`（docker profile 默认 false），否则上传文档永远 PENDING。
- `pg_trgm` 是硬依赖：扩展不可用 = 启动被 `FlywayExtensionGuard` 拦住（不是"兜底通道不可用"），README/docker 前置条件与 `docker/postgres/init.sql` 三处必须同步。
- `RetrievalSchemaIT`（`@Tag("docker")`）承担"一次推送撞掉全部 SQL 未知数"的职责：生成列派生、`to_tsquery` 命中、`content % q` 命中、`ts_rank` 可排序、两个 GIN 存在，五项断言常驻。
- 分词与 `Chunker.chunk` 同在 CHUNKING 步骤内联执行，违反 keyword ADR 后果 2，已在其修订第 4 条登记触发条件。

## 何时重新评估

- P1a-09 实测显示混合检索相对纯向量为负 → 走 keyword ADR 的重新评估分支，先于批 3 验收收口。
- `top4_miss_type` 以"术语相近"为主（而非"完全无关"）→ 提 rerank ADR（`🅜` 的 `RerankService/CosineRerankFallback/DashScopeRerankService` 是现成扫描对象）。
- 真实问答 ≥100 次或用户讲义出现自定义术语 → 评估 jieba 用户词典与 `tokenizer_version` 多版本共存。
- 文档规模到千万级向量、或 PG 关键词通道 Recall 成为瓶颈 → `EsRetriever` 从可选提为托管版默认。
