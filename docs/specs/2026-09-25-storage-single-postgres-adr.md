# ADR: 存储收敛为 PostgreSQL 单库（+ Redis + S3），不使用 MySQL / MongoDB / 独立 ES

- 日期：2026-09-25
- 状态：Accepted
- 相关：[../annona-项目设计文档.md](../annona-项目设计文档.md) §5 / §13、[2026-09-25-chinese-keyword-search-adr.md](./2026-09-25-chinese-keyword-search-adr.md)

## 背景

三个上游仓库的存储选型互不相同，其中最容易让读者误以为"新项目也要 MySQL"的是 MockPilot：

| 仓库 | 存储栈 |
|---|---|
| MockPilot | MySQL 8.0 + MongoDB 7 + Redis 7.2 + Elasticsearch（另含 Milvus 向量库），`.env` 里五套镜像 |
| interview-guide | PostgreSQL 16 + pgvector + Redis + RustFS/S3 |
| summer-checkin | PostgreSQL + pgvector（Prisma，`migrate-to-pgvector.sql`） |

annona 从 MockPilot 借鉴的是混合检索算法（RRF 融合、重排）与工程防护组件（SingleFlight、Resilience4j 分级熔断、多级线程池隔离），**不继承其存储架构**。同时产品主线要求"业务数据 + 向量 + 关键词检索"三者频繁联动：交卷后需原子地写评估结果、更新掌握度、落决策留痕。

## 决策

**统一为 PostgreSQL 16 + pgvector 单库**，配 Redis（缓存 / Stream / 限流 / 在线状态）与 S3 兼容对象存储（原始文件）。

- 向量：pgvector，HNSW 索引，COSINE 距离，统一 1024 维。
- 关键词：`tsvector` + GIN（应用层中文分词后写入）+ `pg_trgm` 模糊兜底。
- 半结构化：`jsonb`（`direction.meta_json`、`evaluation_result.dims_json`、`decision_trace.inputs_json`）。
- 原始文档字节：只进 S3，库里存 `s3_key` 与 `content_hash`。
- MySQL / MongoDB **不使用**；ElasticSearch 仅作为 `Retriever` SPI 的可选实现存在，**默认不部署**。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| MySQL 8 + Milvus（沿用 MockPilot 分离式） | 业务与向量分处两库，跨库无双写事务，需自建补偿任务处理"向量已写、业务回滚"的孤儿数据；恢复路径变成两份，self-host 备份成本显著上升 |
| MySQL 单库（向量交给应用层或后续再补） | 社区版 MySQL 无生产可用的向量索引方案（pgvector 等价物不在社区发行版内），意味着起步就得再加一个中间件，与"clone 下来能跑"冲突 |
| MySQL 9 / MariaDB 的 vector 类型 | 生态、迁移工具、索引选项与运维经验都远不成熟，且仍缺中文全文检索能力与 `pg_trgm` 等价物 |
| MongoDB 存对话消息与面试纪要 | 文档模型确实贴合消息结构，但消息写入必须与面试状态变更同事务；PG 的 `jsonb` 已能表达该结构，多一套库只多一个备份目标 |
| 独立 Elasticsearch 做混合检索 | 详见 `chinese-keyword-search-adr`：四中间件破坏自部署体验，上线初期文档量撑不到 ES 优势区间；此处只登记"默认不部署，SPI 保留退路" |

## 后果与约束

1. **SQL 必须写 PG 方言**：upsert 用 `INSERT ... ON CONFLICT`（不是 `ON DUPLICATE KEY UPDATE`）；禁止依赖 MySQL 的宽松 `GROUP BY`（PG 要求非聚合列全部出现在 GROUP BY 中）；表名/列名统一 snake_case，不加反引号。
2. **云上部署必须选支持 pgvector 的 PostgreSQL**，不能复用已有 RDS MySQL 实例。落地前需确认目标环境能 `CREATE EXTENSION vector`（自建 PG 与多数托管 PG 可装，部分云托管需提工单或换规格）。这会直接影响部署成本估算，属自部署文档必写项。
3. **备份恢复单一入口**：`pg_dump` / `pg_restore`；S3 侧走生命周期策略。`annona export --user` 只面向 PG 实现。
4. 事务边界因此收紧为"一个数据源"：`@Transactional` 只包 PG 写；Redis、S3、LLM、外部 HTTP 一律在事务外（与 AGENTS.md §0.3 一致）。
5. 维度与距离类型是**不可廉价变更**的决策：1024 维 + COSINE 写入 `V1__baseline.sql`，换 embedding 模型需要全量重索引（提供 `annona reindex`），并在 Flyway 中以新列并行的方式灰度，不能原地改列。

## 何时重新评估

- 单表行数量级超过约 5000 万，或向量规模达到千万级、pgvector 索引构建/查询延迟实测不可接受；
- 或 `scripts/rag-eval` 显示 PG 关键词通道的 Recall 明显成为瓶颈（此时把 `EsRetriever` 从"可选"提升为托管版默认，自部署仍保持单库 PG）；
- 或出现真正的多写者 / 分库需求（届时按模块拆库，而不是回到 MySQL）。
