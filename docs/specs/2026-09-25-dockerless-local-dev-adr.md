# ADR: 开发期不依赖 Docker，容器与集成验证全部交给 CI

- 日期：2026-09-25
- 状态：Accepted
- 相关：[../annona-开发计划.md](../annona-开发计划.md) P0-10 / P0-12 / P0-13、[../annona-项目设计文档.md](../annona-项目设计文档.md) §13

## 背景

维护者本机没有 Docker。而 v1 的设计前提是"单库 PG(pgvector) + Redis + S3 三件依赖"：Flyway 迁移、向量检索、`tsvector`/`pg_trgm`、Redis 会话与限流、SSE/WebSocket，这些在纯 JVM 环境里都无法真跑。

如果不在制度上明确"本机验证到哪一层"，会出现两种坏结果：一是开发者反复尝试跑不起来的命令并浪费时间；二是**误以为"本地测不过=代码不对"，于是把集成测试一路删到没有**。

## 决策

1. **开发期只跑代码正确性校验**：`mvn verify`（编译 + 单测 + ArchUnit）、前端 `typecheck` + `build`。**不运行任何 Docker 相关命令或容器测试**。
2. **测试按是否依赖中间件分层**，用 JUnit Tag 区分，本机默认排除：

   | 层 | 标记 | 依赖 | 本机 | CI |
   |---|---|---|---|---|
   | 纯逻辑单测 | 无 tag | 无 IO（算法、状态机、规则、序列化、SQL 构造） | ✅ 默认跑 | ✅ |
   | 切片测试 | `@Tag("slice")` | Mock/Mockito，无外部进程 | ✅ | ✅ |
   | 集成测试 | `@Tag("docker")` | 真实 PG(+vector,citext) / Redis / S3 | ❌ 不跑 | ✅ |
   | e2e / 评测 / 压测 | `e2e.yml`、`rag-eval.yml` | 起服务与浏览器 | ❌ | ✅ 或定时 |

   surefire 默认 `excludedGroups=docker`，CI 用 `-Dgroups=docker` 单跑该组。
3. **容器验证在 CI 有 Docker 的 runner 上完成**，具体分区见本文 §CI 执行矩阵。
4. **P0 出口条件相应改写**：把"全新机器 `docker compose up` + `make quickstart` 一条命令到首页"从**本机验收项**移到 **CI 验收项**；本机只验 `mvn verify` + 前端构建。
5. **本机确实要跑起来时**（P1 起需要看界面）：自行安装 Windows 原生 PostgreSQL 16（含 pgvector、citext）与 Redis（Memurai/官方移植版亦可），配置写 `.env`；README 与本文档记录该路径，但**不把它当作日常验证手段**。
6. **`make` 目标保留但不作为本机默认入口**：`make up`、`make quickstart` 等依赖 Docker 的目标在 CI/部署环境使用；本机日常用 `mvnw` 与 `pnpm` 原始命令。

## CI 执行矩阵（GitHub-hosted ubuntu runner，预装 Docker CLI/Engine 与 Compose v2）

| 验证项 | 方式 | 镜像 / 服务 | 为什么这样选 |
|---|---|---|---|
| 编译 + 纯逻辑单测 + slice + ArchUnit | `mvn -q verify`（默认排除 docker 组） | 无需容器 | 最快反馈，每个 push 都跑 |
| Flyway 迁移演练 + `@Tag("docker")` 集测 | Actions **`services:`**（不是 compose） | `pgvector/pgvector:pg16` + `redis:7-alpine` | services 由 runner 托管，无需写 compose 文件，启动比 compose 快；S3 用本地文件系统的 `FileStorageService` 实现测试，**不引入 MinIO** |
| compose 冒烟（验证 `docker-compose.yml` 本身正确） | `docker compose -f docker/docker-compose.yml config -q` 先做语法校验，再 `up -d --wait` + 探活 | 仓库自带的完整栈 | 这是**唯一真正需要 compose** 的 job；它测的是交付物，不是业务逻辑 |
| 前端 typecheck / lint / build | 直接 npm/pnpm step（`pnpm action` 缓存） | 无需容器 | 不需 Docker |
| Playwright e2e | **容器 job**（`jobs.<id>.container.image`） | `mcr.microsoft.com/playwright:v<版本>-noble`（固定版本） | 官方镜像自带浏览器与依赖，比 `npx playwright install` 快且稳定 |
| RAG 评测（Recall@K/MRR） | step 跑 `scripts/rag-eval` + 上传 artifact | 复用 PG services；Embedding 用**录制结果或本地小模型** | 不依赖真实云端 Key，结果以报告 artifact 留存 |
| 镜像构建与发布 | `docker/build-push-action` | — | 只在 tag / main 上跑 |
| 真实模型连通性 | 定时 `schedule` job（夜间），Key 走 repository secrets | 无需容器 | 不能在 PR 上跑：密钥不可控、波动大、fork PR 拿不到 secrets |

**六条约束**：

1. **必须用 `pgvector/pgvector:pg16`**，不是官方 `postgres:16`（后者无 `vector` 扩展，一上手集测就红）。
2. 集测与 compose job 用 **paths 过滤**触发（仅 `annona-*/**`、`pom.xml`、`docker/**`、`db/migration/**` 变更时跑），否则每个文档 PR 都白等两三分钟。
3. **模型真实调用不进 PR 流水线**；CI 里的模型一律走 `ModelProvider` 的 Fake/录制实现，测试不依赖网络。
4. 密钥只存 GitHub Secrets；fork 发来的 PR 默认拿不到 secrets，所以任何依赖 secrets 的 job 必须能优雅 skip，而不是报错。
5. 评测与冒烟的产物（报告、截图、日志）用 `upload-artifact` 留存，阶段总结里的验收证据指向 artifact 而不是终端截屏。
6. CI 绿是**合并门禁**（branch protection 要求必需检查），因为本机跑不到中间件层——这是本决策的主要代价，必须用门禁补回来。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 为了本地能跑，把 H2 当开发库 | H2 不支持 `vector`/`citext`/`tsvector`/`pg_trgm` 与 PG 方言（`ON CONFLICT`、生成列），要么代码写两套方言，要么"本地绿、线上红"——比不跑更危险 |
| 用 Testcontainers / Docker Desktop 强行本地跑 | 本机没有 Docker，方案不成立；但 **CI 的 runner 有 Docker**，所以容器类验证全部落在那边，本决策不削减测试覆盖 |
| 把集成测试改成 mock 数据库 | mock 掉 SQL 就等于没有验证：Flyway 脚本、向量列序列化、`ON CONFLICT` 幂等这些恰恰是最容易错的地方 |
| 干脆不做集成测试，只留单测 | 本项目最贵的错误正好在中间件边界（迁移、幂等、事务边界）；取消等于把风险推到用户环境 |
| 引入内存版 PG 模拟器（如 `zoobab/pg_mem`、jsqlparser 类） | 生态不成熟，无法覆盖 pgvector，属于负收益的取巧 |

## 后果与约束

1. **纯逻辑与 IO 必须彻底分离**：`planner/mastery`、`planner/guard`、`evaluation/structured`、`knowledge/chunk`、`retrieval/hybrid`（打分与融合部分）、`identity` 口令编码等都应能在无数据库环境下断言——这是架构约束，不是测试偏好。
2. SQL 正确性风险由三道防线补：Flyway 脚本人工评审 + CI 空库迁移演练 + `ddl-auto: validate` 启动校验。任何绕过（比如把 `validate` 改成 `none`）都不允许。
3. **反馈延迟变大**是本决策的直接代价：迁移与检索问题最早在 CI 才暴露。因此 PR 必须等 CI 绿才能合并，禁止"本地没跑但看起来对"就推进。
4. 文档与命令表必须**逐条标明执行环境**（本机 / CI / 部署环境），否则同样的坑会反复踩。
5. 阶段总结的「验收证据」允许且应当包含 **CI 日志链接**，而不是只贴本机输出。
6. `docs/annona-开发计划.md` 与 `AGENTS.md` 的命令清单以本文为准；两处若与本文冲突，以本文修正。

## 何时重新评估

- 本机装上 Docker Desktop / WSL2 后，可把 compose 冒烟与 `@Tag("docker")` 集测放回本地默认执行（其余分层不变）；
- 若 CI 排队/时长成为瓶颈（>15 分钟），再考虑为集成测试建独立 runner 或按需触发。
