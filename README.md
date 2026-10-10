# 年轮 · annona

> 你练过的每一分钟，都会长成下一道题的形状。

**annona 是什么**：自习室 + AI 模拟面试的双功能训练平台。自习室 / 模拟面试 / 知识问答 / 知识库 / 计划日程各入口平级且各自独立成立（知识库入口为 2026-09-27 新增，见 docs/specs/2026-09-27-knowledge-ingestion-adr.md §决策 10），共用一套内核（身份、模型网关、知识库、学习信号、方向字典）。

面试出题不是随机的：决策层以**面试侧自身数据**（历史得分、掌握度、错题、距上次练习天数）为主要依据决定问哪个方向、出多难的题、追问几层、掺多少复习题；当学习方向与面试方向相交时（比如你在自习室备考面试科目），自习室的专注时长与完成率作为辅助证据参与——方向不相交时两线互不干扰。**每一次自动决策的依据都落库、可查看**——系统必须能回答"你凭什么这么考我"。

- 语言 / 许可：Java 21 + Spring Boot 4.1 / React + Vite · **AGPL-3.0**
- 存储：PostgreSQL 16 + pgvector · Redis 7 · S3 兼容对象存储（**不使用 MySQL / MongoDB，默认不部署 ES**）
- 模型接入：**项目不内置任何 API Key**，自部署走 BYOK（自带 Key）

---

## ⚠️ 当前仓库状态（先读这段，避免走弯路）

| 项 | 状态 |
|---|---|
| 设计 / 结构 / 开发计划 / ADR（数量以 `docs/specs/` 目录为准，不在此处维护计数） | ✅ 已定稿，在 [`docs/`](./docs/README.md) |
| AI 协作规范 | ✅ [`AGENTS.md`](./AGENTS.md) |
| 仓库入口文件 | ✅ `README` / `LICENSE`(AGPL-3.0 全文) / `SECURITY` / `CONTRIBUTING` / `CODE_OF_CONDUCT` / `.editorconfig` / `.env.example` |
| **Maven 结构** | ✅ 已拆为 4 个 Java 模块（`annona-common` / `annona-spi` / `annona-infrastructure` / `annona-server`）+ 聚合根 pom；`annona-web` 为 Vite 子项目 |
| **业务代码** | ✅ **P1（a/b/c）+ P2 已落地**：`identity`（含 P2 资料/头像历史）`study`（含 P2 年度统计/在线共学）`shared/direction` `knowledge`（入库+分块+向量化）`retrieval`（双通道 RRF）`qa`（SSE 流式问答）`questionbank`（SKILL 驱动异步出题+容量校验）`interview`（组卷去重+fencing 状态机+续面+交卷幂等）`evaluation`（异步评估+难度加权+可比性+PDF 导出）`resume`（上传解析+AI 分析）`llmprovider`/`usage`（BYOK 六用途加密+token 计量+日配额）`planner`（掌握度+规则链+保护前置+decision_trace+反驳降权）`plan`（MD 拆任务+打卡联动）；前端有登录/自习室（年度热力图/3D 小岛/沉浸模式/场景主题/匿名共学）/知识库/问答/面试中心/**语音面试**/报告与**可解释决策面板**/**计划工作室**/个人资料页面；`voice`（P3 批 1：ASR/TTS 端口 + `/ws/voice` 会话；批 2：逐题对话轮 + 句级并发 TTS + 报告多态化）已建包；`schedule`/`agent` **尚不存在包**（P4 再建，不是“只有 package-info”） |
| 已落地的技术基座 | ✅ `Result`/异常体系、`traceId` 过滤器、四类线程池 + Micrometer、启动 fail-fast（缺 KEK / 缺 pgvector 拒起）、Flyway V1–V21（身份/方向 → 学习采集 → 知识库+pgvector → 问答 → 面试会话 → 计量 → 题库 → 评估 → 简历 → 决策留痕/规则声誉 → 计划 → 语音会话/对话轮/报告多态化），已应用迁移由 pre-commit 冻结+登记双机检、`@Modifying` 事务覆盖与原生查询真库地雷机检（datetime 标量硬转型 / GROUP BY·ORDER BY 命名参数）、JaCoCo 60% 底线（关键包 85% 专项）、前端 ESLint/vitest 机检、ArchUnit 结构规则（含跨模块白名单边与“该边确实存在”的反空转断言）、`.githooks/`（八道门禁）、六 job 的 `ci.yml`（含 docker-it 真库集测，action 全 SHA pin）、compose 三阶段 Dockerfile、`Makefile`、CI 密钥扫描 |
| **施工阶段** | ✅ **P1 已关账（P1a/P1b/P1c 各自用户裁决关账，最后一片 P1c 于 2026-10-01）**——出口①（真模型面板取证）与出口④（A/B 真实数字）接受为部署/CI 待办遗留；**P2 已关账（2026-10-06·收尾批，[CI run 37427468155](https://github.com/Ember452/annona/actions/runs/37427468155) 六 job 全绿含 docker-it 真 PG）**——fps/P95 部署实测与数据导出（[DATA_EXPORT_PLAN](./docs/plans/DATA_EXPORT_PLAN.md)）按用户指示接受为遗留/转移；**P3 语音 🔶 doing**（[P3_VOICE_PLAN](./docs/plans/P3_VOICE_PLAN.md) 分三批：批 1 端口+WS+流式 ASR 已入 main，批 2 句级并发 TTS+题库驱动对话轮+评估接入已入 main（`5e9dfc7..901993d`，[CI run #117](https://github.com/Ember452/annona/actions/runs/38051324983) 六 job 全绿含 docker-it 真 PG），批 3 延迟实测与压力面）；P4/P5 todo。任务清单与状态以 [docs/annona-开发计划.md](./docs/annona-开发计划.md) 为唯一真相源 |
| Docker 相关 | 📄 文件已交，**本机不跑**（无 Docker），验证全部在 CI（见下） |

> **一句话定位现状**：地基与门禁就位；真实资料可流式问答（P1a 出口），两个方向可完成一场带评分标准与可解释报告的 AI 面试（P1b 出口），**面试不再是随机出卷且能逐条回答“凭什么这么考我”**（P1c 出口：规则链/保护留痕 + 面板 + 反驳降权改变后续决策）；自习室有年度热力图、3D 小岛、沉浸模式、场景主题与匿名共学，计划可由 MD 拆任务并与打卡联动（P2 出口）。**P3 语音尚在施工中**：fake 全链已在真库跑通 WS→字幕→落库，逐题对话轮与“语音/文字同一评估引擎”已由 docker-it 在真 PG 上钉住（P3 批 2；端到端延迟实测与压力面属批 3）。遗留：真模型端到端取证与 A/B 真实数字未跑（P1 出口①④），P2 的 fps/P95 部署实测与数据导出（[DATA_EXPORT_PLAN](./docs/plans/DATA_EXPORT_PLAN.md)）同属部署环境遗留；且**P1 的两个取证必须跑在 P0 修复后的版本上**——修复前 demo 在默认参数下触发不了任何调整规则（见 [planner ADR 修订 1](./docs/specs/2026-09-30-planner-decision-kernel-adr.md)）。
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

### 纯前端模式（不启动后端调样式/交互）

```bash
echo 'VITE_MOCK_BACKEND=1' > annona-web/.env.local   # 该文件已被 gitignore
cd annona-web; pnpm dev
```

开启后**所有 API 请求由本地 fixtures 应答**（`src/mocks/mockBackend.ts`，axios adapter 层拦截）：任意邮箱密码点登录即进，方向/自习室页有假数据，未实现的端点返回业务错误码走既有错误态。仅用于前端细节开发——**行为规格仍以 CI 上的真后端验证为准**，不要据 mock 行为写后端契约。未开启 mock 且后端未启动时，应用会停在"无法连接后端"面板（不是登录页），点"重试连接"即可在后端就绪后进入。

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
3. SQL 正确性风险靠两道防线补：Flyway 脚本评审（本机）+ **CI 上的真实空库迁移演练**；`ddl-auto: validate` 自 P1a-01 起（已有 8 个 JPA Entity + `IdentitySchemaValidateIT`）真实校验实体↔迁移一致性——但它只校字段类型不校索引，索引漂移仍要靠迁移评审与集测。
4. 声明"验证通过"必须贴出**实际跑过的命令与输出**（`AGENTS.md` §0.8）。
5. 本机跑不到中间件层，所以 **PR 必须等 CI 变绿才算完成**；`@Tag("docker")` 测试与 compose 冒烟的凭证可以是 CI 日志链接。

---

## 本地运行需要的外部依赖（P1 起才用得上）

本机要真跑起来时必须自行准备 PostgreSQL 16（含 `vector` 与 `citext` 扩展）与 Redis，配置写进 `.env`（模板见 [`.env.example`](./.env.example)）。没有这两个组件时，**仍然可以正常完成编译、单测与前端构建**——这正是上面的开发循环。

> **公网部署前置（安全）**：`register` / `login` 目前**没有限流**（identity ADR 要求的 `@RateLimit` 基建随 P1b-10 落地）——对外暴露前必须自备反代层限流，否则脚本注册可无成本消耗 scrypt 资源并刷库。同理：HTTPS 部署必须设 `ANNONA_SESSION_COOKIE_SECURE=true`；启用 `platform` 身份模式前先读 `.env.example` 的信任边界注释与 `docs/specs/2026-09-26-identity-provider-modes-adr.md`。

---

## 许可证

本仓库整体采用 **AGPL-3.0**：许可全文已逐字落在根目录 [`LICENSE`](./LICENSE)（FSF 标准文本，661 行），`pom.xml` 的 `<licenses>` 与之一致。`skills/` 目录下的内置 `SKILL.md` 采用 **CC-BY-4.0**（便于站外引用与社区改写；该声明随 P1b-01 建 `skills/` 目录时一并加入）。

> `LICENSE` 文件必须保持与 FSF 原文逐字一致，**不得修改、不得“适配项目名”**；需要声明项目自身的版权时，另写头部注释或 `NOTICE`，不要动 `LICENSE`。

## 上游致谢

annona 与以下三个开源项目同为本人所有；前端 UI 与部分工程机制**直接改造自三仓代码**（版权同属本人，不构成第三方代码引入），整体以 AGPL-3.0 发布：

- interview-guide（Spring Boot + Spring AI 的简历分析 / 模拟面试 / RAG 知识库平台）
- MockPilot（混合检索、SingleFlight、Resilience4j、多级线程池隔离的设计参考）
- summer-checkin（自习室交互与学习数据采集的设计参考）

三仓路径与逐任务借鉴映射见 [开发计划 §借鉴地图](./docs/annona-开发计划.md)。
