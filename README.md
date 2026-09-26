# 年轮 · annona

> 你练过的每一分钟，都会长成下一道题的形状。

**annona 是什么**：把「自习室采集的学习行为」变成「AI 面试官的出题依据」的训练闭环平台。自习室 / 模拟面试 / 知识问答 / 计划日程四个入口平级，共用一套内核（身份、模型网关、知识库、学习信号、方向字典）。

它不是三个工具的合集：`专注时长与完成率 + 历史得分 + 距上次练习天数` 会决定下一次面试问哪个方向、出多难的题、追问几层、掺多少复习题，而**每一次自动决策的依据都落库、可查看**——系统必须能回答"你凭什么这么考我"。

- 语言 / 许可：Java 21 + Spring Boot 4.1 / React + Vite · **AGPL-3.0**
- 存储：PostgreSQL 16 + pgvector · Redis 7 · S3 兼容对象存储（**不使用 MySQL / MongoDB，默认不部署 ES**）
- 模型接入：**项目不内置任何 API Key**，自部署走 BYOK（自带 Key）

---

## ⚠️ 当前仓库状态（先读这段，避免走弯路）

| 项 | 状态 |
|---|---|
| 设计 / 结构 / 开发计划 / 10 条 ADR | ✅ 已定稿，在 [`docs/`](./docs/README.md) |
| AI 协作规范 | ✅ [`AGENTS.md`](./AGENTS.md) |
| 仓库入口文件 | ✅ `README` / `LICENSE`(AGPL-3.0 全文) / `SECURITY` / `CONTRIBUTING` / `CODE_OF_CONDUCT` / `.editorconfig` / `.env.example` |
| **Maven 结构** | ✅ 已拆为 4 个 Java 模块（`annona-common` / `annona-spi` / `annona-infrastructure` / `annona-server`）+ 聚合根 pom；`annona-web` 为 Vite 子项目 |
| **业务代码** | 🚧 `io.annona.modules.*` 与 `annona-infrastructure` 下**只有 `package-info.java`**，16 个业务模块尚无任何实现类（从 P1a 起逐个填充） |
| 已落地的技术基座 | ✅ `Result`/异常体系、`traceId` 过滤器、四类线程池 + Micrometer、启动 fail-fast（缺 KEK / 缺 pgvector 拒起）、Flyway V1（身份 7 表 + `direction` 主数据）、ArchUnit 七条、`.githooks/`、5+1 job 的 `ci.yml`、compose 三阶段 Dockerfile、`Makefile`、CI 密钥扫描 |
| **施工阶段** | 🔶 **P0 收尾中**：剩 P0-15（GitHub 网页设置）与 P0 评审整改项；任务清单见 [docs/annona-开发计划.md](./docs/annona-开发计划.md) |
| Docker 相关 | 📄 文件已交，**本机不跑**（无 Docker），验证全部在 CI（见下） |

> **一句话定位现状**：地基与门禁已就位，产品功能一行都没写。下一个阶段是 P1a（采集 + 知识库 + 检索 + 问答）。
> 目标结构与当前代码的差异，以 [docs/annona-项目结构.md](./docs/annona-项目结构.md) §12 的状态列与 [开发计划](./docs/annona-开发计划.md) 「当前进度」表为准。

---

## 零上下文接手：按这个顺序读

```text
1. AGENTS.md                     硬规则、DoD、commit 规范、文档义务（必读）
2. docs/annona-项目设计文档.md     做什么、功能全景、领域模型、决策层算法、Non-goals
3. docs/annona-项目结构.md         代码放哪、Maven 模块、包边界、ArchUnit 规则
4. docs/annona-开发计划.md          现在该做哪件事 + §借鉴地图（每个任务对应上游哪个文件）
5. docs/specs/*-adr.md            为什么这么定、否决了什么（改数据模型/边界前必读）
6. docs/architecture/overview.md  分层、五个 SPI、一次请求的生命周期
```

**关键约定**：`docs/annona-开发计划.md` 的 §借鉴地图 把每个待开发任务钉到了三个参考项目的确切文件路径上，**开工前必须先扫描对应路径**（`AGENTS.md` §4 的强制义务）。参考 = 借鉴机制与测试用例清单；前端 UI 层按借鉴地图 §D 直接**改造复用**（三仓均为本人项目）。

---

## 开发循环（本机无 Docker）

**开发期只跑代码正确性校验，不运行任何 Docker / 容器相关命令与测试**——本机没有 Docker。容器类验证（compose 起服务、集成测试、e2e、RAG 评测）交给 CI（GitHub Actions 有 Docker）与自部署环境。

### 本机可跑（日常验证就是这些）

```bash
.\mvnw.cmd -B -q verify                                   # 编译 + 单测 + slice + ArchUnit（默认已排除 docker 组）
.\mvnw.cmd -B -q test -Dtest=ArchitectureTest             # 单跑一个纯逻辑测试类
cd annona-web; pnpm install; pnpm typecheck; pnpm build    # 前端（产物直接写入 annona-server 的 static/）
python scripts\ci\validate-workflows.py                   # 改过 .github/workflows 时必跑
```

> 上面四条**现在都能跑**（不依赖 Docker、PG、Redis）。完整分区见 [AGENTS.md §8](./AGENTS.md)。
>
> **为什么最后一条重要**：GitHub 对无法解析的 workflow 文件是**静默不运行** —— 不报错、不产生
> check run，只会在 Actions 页面留一行提示。曾因此让一整批 CI 改动实际从未执行，所以改
> `.github/workflows/**` 必须本地先校。

### 本机不跑（CI / 部署环境执行）

| 项 | 由谁跑 |
|---|---|
| `docker compose up`、compose 冒烟 | CI `ci.yml` 的 compose job |
| 集成测试（需 PG + pgvector + citext + Redis） | CI，测试打 `@Tag("docker")`，本机默认排除 |
| Playwright e2e、RAG 评测、语音压测 | CI（`e2e.yml` / `rag-eval.yml`）与手工验证环境 |
| `annona-cli` 对真实库的 `reindex` / `export` | 用户自部署环境或 CI |

### 因此开发期的验证口径

1. 业务逻辑写成**纯逻辑单测可覆盖**的形态：算法、状态机、规则链、边界值都用 `unit` 测试断言，关键包配 golden 快照。
2. 依赖中间件的代码通过 **SPI + Fake 实现**测试（`annona-spi` 的意义所在），不把 IO 混进算法。
3. SQL 正确性风险靠两道防线补：Flyway 脚本评审（本机）+ **CI 上的真实空库迁移演练**；`ddl-auto: validate` 要等有 JPA Entity 之后才真正校验东西（现阶段无 Entity，它是空转的，不要当成保护）。
4. 声明"验证通过"必须贴出**实际跑过的命令与输出**（`AGENTS.md` §0.8）。
5. 本机跑不到中间件层，所以 **PR 必须等 CI 变绿才算完成**；`@Tag("docker")` 测试与 compose 冒烟的凭证可以是 CI 日志链接。

---

## 本地运行需要的外部依赖（P1 起才用得上）

本机要真跑起来时必须自行准备 PostgreSQL 16（含 `vector` 与 `citext` 扩展）与 Redis，配置写进 `.env`（模板见 [`.env.example`](./.env.example)）。没有这两个组件时，**仍然可以正常完成编译、单测与前端构建**——这正是上面的开发循环。

---

## 许可证

本仓库整体采用 **AGPL-3.0**：许可全文已逐字落在根目录 [`LICENSE`](./LICENSE)（FSF 标准文本，661 行），`pom.xml` 的 `<licenses>` 与之一致。`skills/` 目录下的内置 `SKILL.md` 采用 **CC-BY-4.0**（便于站外引用与社区改写；该声明随 P0 建 `skills/` 目录时一并加入）。

> `LICENSE` 文件必须保持与 FSF 原文逐字一致，**不得修改、不得“适配项目名”**；需要声明项目自身的版权时，另写头部注释或 `NOTICE`，不要动 `LICENSE`。

## 上游致谢

annona 与以下三个开源项目同为本人所有；前端 UI 与部分工程机制**直接改造自三仓代码**（版权同属本人，不构成第三方代码引入），整体以 AGPL-3.0 发布：

- interview-guide（Spring Boot + Spring AI 的简历分析 / 模拟面试 / RAG 知识库平台）
- MockPilot（混合检索、SingleFlight、Resilience4j、多级线程池隔离的设计参考）
- summer-checkin（自习室交互与学习数据采集的设计参考）

三仓路径与逐任务借鉴映射见 [开发计划 §借鉴地图](./docs/annona-开发计划.md)。
