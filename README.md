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
| 设计 / 结构 / 开发计划 / 8 条 ADR | ✅ 已定稿，在 [`docs/`](./docs/README.md) |
| AI 协作规范 | ✅ [`AGENTS.md`](./AGENTS.md) |
| **代码实现** | 🚧 **仅 Spring Boot Initializr 骨架**（`src/` 共 3 个文件），尚未按目标结构拆分模块 |
| **施工阶段** | **P0 未开始**，任务清单见 [docs/annona-开发计划.md](./docs/annona-开发计划.md) 的 P0 表 |
| Maven 多模块 / 16 个业务包 | ❌ 尚未创建（P0-02 / P0-04） |
| Docker 相关文件 | 📄 已在文档中设计，本机**不运行**（见下） |

**目标结构与当前结构的差异，以 [docs/annona-项目结构.md](./docs/annona-项目结构.md) §12 的 8 步重组清单为准**——那份清单就是 P0 的施工图。

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

**关键约定**：`docs/annona-开发计划.md` 的 §借鉴地图 把每个待开发任务钉到了三个参考项目的确切文件路径上，**开工前必须先扫描对应路径**（`AGENTS.md` §4 的强制义务）。参考 = 借鉴机制与测试用例清单，不复制代码。

---

## 开发循环（本机无 Docker）

**开发期只跑代码正确性校验，不运行任何 Docker / 容器相关命令与测试**——本机没有 Docker。容器类验证（compose 起服务、集成测试、e2e、RAG 评测）交给 CI（GitHub Actions 有 Docker）与自部署环境。

### 本机可跑（日常验证就是这些）

```bash
.\mvnw.cmd -q verify                                          # 编译 + 单测 + ArchUnit
.\mvnw.cmd -q test -DexcludedGroups=docker                    # 显式排除需要中间件的测试
.\mvnw.cmd -q test -Dtest=MasteryCalculatorTest              # 单跑一个纯逻辑测试类
cd annona-web; pnpm install; pnpm typecheck; pnpm build       # 前端类型与构建（P0-09 后可用）
```

> 当前仓库处于 P0 之前：上面四条里**只有 `mvn -q verify` 现在真能跑**（`-Dtest=` 的类名与 `annona-web` 目录都要等对应任务完成）。完整分区见 [AGENTS.md §8](./AGENTS.md)。

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
3. SQL 正确性风险靠三道防线补：Flyway 脚本评审 + CI 迁移演练 + `ddl-auto: validate` 启动校验。
4. 声明"验证通过"必须贴出**实际跑过的命令与输出**（`AGENTS.md` §0.8）。
5. 本机跑不到中间件层，所以 **PR 必须等 CI 变绿才算完成**；`@Tag("docker")` 测试与 compose 冒烟的凭证可以是 CI 日志链接。

---

## 本地运行需要的外部依赖（P1 起才用得上）

本机要真跑起来时必须自行准备 PostgreSQL 16（含 `vector` 与 `citext` 扩展）与 Redis，配置写进 `.env`（模板见 [`.env.example`](./.env.example)）。没有这两个组件时，**仍然可以正常完成编译、单测与前端构建**——这正是上面的开发循环。

---

## 许可证

本仓库整体采用 **AGPL-3.0**（`pom.xml` 已声明）。仓库根 `LICENSE` 全文文件待补（任务见开发计划 P0-14），在补齐前请以 `pom.xml` 的 `<licenses>` 声明与 <https://www.gnu.org/licenses/agpl-3.0.html> 原文为准。`skills/` 目录下的内置 `SKILL.md` 采用 CC-BY-4.0，便于社区引用与改写。

## 上游致谢

annona 是全新独立实现，**不搬运任何代码**，但设计参考了以下开源项目的能力与工程做法，并因此遵循 AGPL-3.0 的传染性约束：

- interview-guide（Spring Boot + Spring AI 的简历分析 / 模拟面试 / RAG 知识库平台）
- MockPilot（混合检索、SingleFlight、Resilience4j、多级线程池隔离的设计参考）
- summer-checkin（自习室交互与学习数据采集的设计参考）

三者均为本地参考项，具体到“哪个任务参考了哪个文件的哪一段机制”，见 [开发计划 §借鉴地图](./docs/annona-开发计划.md)。
