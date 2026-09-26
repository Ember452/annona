# AGENTS.md — annona · AI 协作行为规范

**项目一句话**：年轮（annona）把「自习室采集的学习行为」变成「AI 面试官的出题依据」——一个学习行为驱动的备考与面试训练闭环。

本文件是 AI 参与本项目开发时的**强制行为规范**。技术细节不在此重复，只在这里规定"怎么做、不能做什么、做完必须留下什么"。

---

## 0. 绝对规则（无条件适用，违反即返工）

1. **绝不允许擅自 `git commit` / `git push`**。仅当用户在当前对话中明确下达提交/推送指令时才执行；"把代码写好""继续"等表述**不构成**提交授权。同样禁止：擅自建分支、改 git config、rebase、`reset --hard`、force push、跳过 hook（`--no-verify`）。
2. **绝不硬编码任何 API Key、密码、Token**。密钥只经环境变量 `.env` / `ANNONA_SECRET_KEY` 注入，且不入仓（详见 §6 触发项）。
3. **绝不在数据库事务内**调用 LLM、S3、外部 HTTP。事务只包本地库写。
4. **绝不 `throw new RuntimeException(...)`**；业务失败一律 `BusinessException(ErrorCode.X, msg)`。
5. **绝不把 Entity 返回给前端**，也不在 Service 里散落 `@Value`、不手写散落的限流/重试逻辑。
6. **绝不引入未讨论的依赖或框架**。需要引入时先给出：解决什么问题、不用它的代价、为什么现有方案不够、是否可移除。
7. **绝不删除你不理解的代码**。发现可疑的死代码或设计问题：说出来，不要顺手清理。
8. **绝不在没有跑过验证命令的情况下声明"完成/修好了/测试通过"**。命令与输出是唯一凭证。
9. **开发期间不跑任何 Docker 命令与容器测试**（本机无 Docker）。依赖中间件（PG/Redis/S3）的测试一律打 `@Tag("docker")` 并在本机排除；容器类验证由 CI 有 Docker 的 runner 负责。不得为了"本地能跑"而 mock 掉 SQL 与向量能力或改用 H2 当开发库（见 `docs/specs/2026-09-25-dockerless-local-dev-adr.md`）。

---

## 1. 项目定位（判断功能该不该做）

**主线**：`study / plan / checkin → shared.signal → planner（训练决策层） → interview → evaluation → decision_trace → 可解释面板 → 反哺 plan`。

三条设计主张，任何改动都要能对齐：

- **可解释优先于准确**：系统自动做的每个决定（问这题、难度升到 4、追问 3 层）都必须能回答"凭什么"。做不到可解释的决策不许上线。
- **入口平级，内核共用**：自习室 / 模拟面试 / 知识问答 / 计划日程四个入口无主次；`direction` 主数据、学习信号、检索、模型网关是共用内核。
- **内容不自产**：技术方向内置 `SKILL.md`，非技术方向由用户讲义驱动出题。我们不承诺自己编写的法考 / CPA 考点。

**Non-goals（出现这些需求先拒绝，除非推翻 ADR）**：神态表情分析、群聊与 @AI、多人无领导小组、徽章积分体系、付费墙锁核心功能、workspace/team/RBAC、移动端原生、自研模型与微调、ES 作为默认检索后端。

---

## 2. 文档地图（动手前先查这里）

| 我想知道… | 去读 | 什么时候必须更新它 |
|---|---|---|
| 仓库现在到底是什么状态、哪些命令真能跑 | `README.md`（根目录，含当前进度横幅） | 阶段切换时更新状态表 |
| 做什么、给谁、有哪些功能、领域模型与表 | `docs/annona-项目设计文档.md` | 功能范围或数据模型变化 |
| 代码放哪、包边界、依赖方向、命名规范 | `docs/annona-项目结构.md` | 新增/移动模块、调整包结构 |
| 全局分层、一次请求的生命周期、五个 SPI | `docs/architecture/overview.md` | 跨模块流程变化 |
| 某个难懂模块的内部设计 | `docs/architecture/<module>.md`（索引见 `INDEX.md`） | 该模块内部结构调整 |
| 某个决策为什么这么定、否决了什么 | `docs/specs/YYYY-MM-DD-<topic>-adr.md` | 见 §6 触发清单 |
| 跨 ≥3 模块的改造方案 | `docs/plans/<TOPIC>_PLAN.md` | 计划推进或中止 |
| 阶段级进度（P0–P5 到哪了） | `docs/annona-开发计划.md` 顶部「当前进度」表 | 阶段开工与出口时 |
| 任务级进度 | 对应 GitHub issue 的开关状态 | 任务完成时关闭并回链 commit |
| 当前阶段做到哪、遗留什么 | `docs/reports/P<n>-<名>-阶段总结.md` | 每个阶段收尾（见 §7） |
| 指标怎么测、实测结果 | `docs/tests/指标测试-<模块>.md`、`docs/benchmarks/<主题>_YYYYMMDD.md` | 每次跑评测/压测 |
| 接口清单 | `docs/api/` 或 SpringDoc `/v3/api-docs` | 由脚本生成，不手写 |
| 阶段任务与它的借鉴对象确切路径 | `docs/annona-开发计划.md` §借鉴地图（A–E 五张表） | 发现某板块不在表内时，补上路径 |
| 阅读顺序与写作约定 | `docs/README.md` | 新增文档时同步登记 |

**规则**：文档链接一律相对路径；同一内容不复制到两处（改为链接）；过期文档不删，文件头标 `> ⚠️ Superseded by <link>`。

---

## 3. 行为规范

> 取向：**偏向谨慎而非速度**。琐碎任务可用判断力加速；"简单到不需要设计"从来不是真的。

### 3.1 Think Before Coding

不假设，不隐藏困惑，把取舍摆出来。动手实现前必须：

- **显式说出你的假设**。不确定就问，别默默猜。
- 存在多种合理解释时，**把它们都列出来**，不要替用户 silently 选一个。
- 存在更简单的做法时，**说出来并推回去**——即使用户没问。
- 看不懂的地方**停下来**，指名说清"哪里不清楚"，再问。
- 与本文档或既有设计冲突时，先指出冲突并给出建议，等确认，再动手。

### 3.2 Simplicity First

用能解决问题的**最少**代码，不做任何投机式设计。

- 不做需求之外的功能；单使用的代码不抽抽象；不加没被要求的"灵活性/可配置性"；不为不可能的场景写异常处理。
- 写了 200 行而它能是 50 行 → 重写。
- 自检问一句："资深工程师会不会说这过度设计了？" 会 → 简化。
- 本项目具体形态：一个 Service 只做编排；`planner` 的规则各自独立可关（但规则总数按需求来，不预留空规则）；SPI 只保留已定的五个，不要预感未来需要而先加。

### 3.3 Surgical Changes

只碰你必须碰的，只清理你自己制造的垃圾。

编辑既有代码时：

- 不"顺手改进"相邻代码、注释、格式；不重构没坏的东西。
- **匹配既有风格**，哪怕你会用别的写法。
- 发现无关的死代码 → 提出，不删。

你的改动造成孤儿时：

- 删掉**因你的改动而**未被引用的 import / 变量 / 方法 / 配置项。
- 不删既有死代码，除非被明确要求。

**检验标准**：diff 里每一行都能直接追溯到用户的需求。出现无关的格式化改动即说明你没克制住。

### 3.4 Goal-Driven Execution

把任务转成**可验证目标**，循环直到验证通过；弱标准（"让它能跑"）必然要反复澄清。

| 原始任务 | 转成的可验证目标 |
|---|---|
| 加校验 | 先写非法输入的失败测试，再让它通过 |
| 修 bug | 先写一个复现该 bug 的失败测试，再让它通过 |
| 重构 X | 重构前后测试都通过，且行为无变化（golden 快照比对） |
| 提升检索召回 | 先跑 `scripts/rag-eval` 记基线，改后重跑，Recall@K 有实测提升 |

多步任务先给简短计划：

```text
1. [步骤] → verify: [检查什么]
2. [步骤] → verify: [检查什么]
3. [步骤] → verify: [检查什么]
```

这套规范在起作用的标志：diff 里没有多余改动、因过度设计而返工的情况减少、澄清问题出现在实现之前而不是搞错之后。

---

## 4. 代码质量约束

**结构与边界（改完必自查）**

- 依赖方向只能是 `modules → spi → common`；`modules/*` 之间禁止 import，除白名单 `interview/orchestrator → planner/advisor`。跨模块通信只有两种：只读走 `XxxQueryService` / `shared` 读模型；写走领域事件。
- 分层：`Controller`（路由、校验、委托）→ `Service`（编排，`@Transactional` 只在此层且范围最小）→ `Repository`（JPA，自定义查询用方法名或 `@Query`）。
- 异常出口分两类：**业务失败**返回 HTTP 200 + `Result.error(code, msg)`；**路由/传输层错误**（404/405/400/500）返回真实 HTTP 状态码 + 同样的 `Result` 体。不得把后者也压成 200（会吞掉故障信号并伪装 SPA fallback 缺失）。
- 新增业务模块 = 在 `annona-server` 加包 + 更新 ArchUnit 白名单，**不动 pom**；出现第二个可部署产物才新建 Maven 模块。
- 基础设施能力放 `annona-infrastructure` 或 `common`，禁止散落到业务 Service。
- `io.annona.modules.<name>` 顶层包与 `io.annona.shared.*` 必须有 `package-info.java` 声明职责与允许依赖（子包不强制，避免堆无用文件）。

**借鉴扫描义务（写任何一块前先做）**

1. 到 `docs/annona-开发计划.md` §借鉴地图 找到本任务对应的扫描路径，**先读后写**（可派 Explore subagent 批量读）。未扫描 = 任务未开始。
2. 在该任务的 commit 正文留四行借鉴说明（无代码提交时写进阶段 issue）：读过哪些文件 → 借鉴哪个机制 → 必须改掉的不适配点及理由 → 上游根本没有的部分。
3. 借鉴 = 机制、结构、参数取值、**测试用例清单**（上游 `summer-checkin/tests/` 与 `interview-guide/frontend/src/**/*.test.ts` 是现成的行为规格）；不是复制代码。若确实搬了代码：在文件头保留 AGPL 来源注释并在 issue 记录；**成片（跨文件）搬运才需要先征得用户同意**。
4. 地图里没有的新板块，扫完把路径回写进地图——**地图不完整本身就是缺陷**。
5. **琐碎任务例外**：纯文档修改、错字修正、单行配置调整可省略四行借鉴说明（但仍需先查地图确认无对应条目）。本例外不适用于任何业务代码与数据结构改动。

**命名与风格**

- 后缀：`XxxEntity` / `XxxRequest` / `XxxResponse` / `XxxDTO` / `XxxRepository` / `XxxMapper`；请求体优先 `record`；Entity↔DTO 一律 MapStruct。
- 2 空格缩进、无通配符 import、避免内联全限定类名、构造器注入 + `@RequiredArgsConstructor`。
- 命名一致性优先：同一概念在全仓只用一个词（`direction` 不混用 `topic/subject/category`；`session` 不混用 `round/conversation` 表达同一物）。
- 方向引用一律走 `direction.id` 外键（`key` 只在 owner 内唯一，存字符串无法定位归属），禁止用自由文本表示方向。

**测试最低门槛**

- 关键纯逻辑包（`planner/mastery`、`planner/guard`、`evaluation/structured`、`knowledge/chunk`、`infrastructure` 的分词与向量序列化）**必须有纯逻辑单测 + golden 快照**，覆盖率阈值 85%；其余 60%。**阈值从 P1a 起生效**（P0 只有项目骨架，卡覆盖率只会逼人写空测试）。
- **测试分层（本机无 Docker，见 §0.9 与 `specs/2026-09-25-dockerless-local-dev-adr.md`）**：无 tag = 纯逻辑单测（本机默认跑）；`@Tag("slice")` = Mockito 切片；`@Tag("docker")` = 需真实 PG/Redis/S3，**本机不跑，surefire 默认 excludedGroups=docker，仅 CI 以 `-Dgroups=docker` 执行**。
- 测试意图用中文 `@DisplayName` 描述，复杂场景 `@Nested` 分组。
- 改动公共能力必须跑 `mvn verify`；改动必须附带能证伪它的命令。

**完成定义（DoD）**：已按借鉴地图扫描并留下借鉴说明 → 编译通过 → 本机测试集（unit + slice）通过 → ArchUnit 无违规 → 无未用 import/孤儿代码 → 文档按 §2 同步 → 若涉及决策或指标，ADR / 评测文档已落 → **输出实际跑过的命令与结果**（容器类与 `@Tag("docker")` 集成测试的凭证可以是 CI 日志链接）。

---

## 5. Commit 与 PR 规范

> 前提：这些规则只在**用户明确要求提交**时生效（见 §0.1）。

**语言**：commit message 与 PR 描述**一律英文**。代码注释允许中文，测试 `@DisplayName` 用中文。

**粒度**：一个 commit = 一个完整且单一的逻辑变更，能独立说明"为什么"，独立可回滚。

- ✅ 正确拆分：`feat(planner): add sample-size guard before difficulty adjustment` + `test(planner): cover difficulty guard boundaries`
- ❌ 禁止：`update` / `misc` / `fix bugs`；把"重命名包"+"改逻辑"+"格式化"塞进一个 commit；顺手格式化无关文件混入功能 commit。
- 单 commit 建议 ≤ 400 行变更；超过则拆分或在 body 说明为何无法拆。

**格式（Conventional Commits）**

```text
<type>(<scope>): <imperative subject, ≤72 chars, no trailing period>

<body: 每行 ≤100 字符，解释 WHAT changed 与 WHY，而非 how>

Refs: #<issue>          ← 仅在关联 bug / 提案类 issue 时写
Task: P0-01             ← 对应 docs/annona-开发计划.md 的任务 ID，必写

Signed-off-by: ...      ← DCO 签名，提交时带 -s
```

- `type ∈ feat | fix | refactor | perf | test | docs | build | ci | chore | revert`。
- `scope` 用模块名：`planner` `interview` `evaluation` `retrieval` `knowledge` `questionbank` `study` `plan` `voice` `schedule` `agent` `usage` `identity` `web` `infra` `docs`。
- 破坏性变更：type 后加 `!`（`refactor(direction)!: replace subject string with direction_key fk`）并在 body 写迁移方案。
- subject 用祈使句现在时（`add`，不是 `added` / `adds`）。
- **任务级进度不建 issue**：开发计划的任务表是唯一真相源，**每个阶段一个 issue**（阶段开工前建，作为对外可见的进度摘要）。因此 commit 不强制 `Refs:`，改用 `Task: P<n><子>-<序号>` 回指任务 ID。
- 只有 `fix` / `feat` 进入 changelog，其余必须用可忽略的 type 或 `*` 前缀。

**提交前自查**：`mvn -q verify` 通过、ArchUnit 无违规、diff 里没有无关格式化、不含任何真实密钥（gitleaks 已挂在 pre-commit，禁止 `--no-verify` 绕过）。

**PR 要求**：标题同 commit 规范；描述包含"改了什么、为什么、如何验证（贴命令与输出）、是否影响决策输入或检索指标"；改数据模型/决策算法时链接对应 ADR。

---

## 6. 文档同步义务：关键决策 → 立即写 ADR

**以下任一条命中，必须在实现的同时新建 `docs/specs/YYYY-MM-DD-<topic>-adr.md`，不许"写完代码再补"**：

| 触发 | 例 |
|---|---|
| 任何数据模型 / Flyway 变更 | 新表、字段语义变化、外键策略调整 |
| 模块边界或依赖方向变化 | 新增跨模块调用、想把 X 从 Y 里拆出来 |
| 决策层的算法与保护规则 | 掌握度公式、难度映射阈值、样本量下限、占比上限 |
| 否决了某个明显可行的方案 | 不启用 ES、不做静默 fallback KEK、不拆 16 个 jar |
| 新依赖 / 框架 / 外部服务 | 分词器、PDF 库、模型供应商 |
| 性能或成本取舍 | 首句 TTS 优先、批量而非逐条向量化 |
| 许可与合规 | AGPL 边界、SKILL.md 用 CC-BY-4.0 |
| 安全与密钥处理 | Key 用途五分、日志脱敏 |

**ADR 格式（一页内，必须含"否决的备选"）**：

```markdown
# ADR: <决策，一句话能说清>
- 日期 / 状态：Accepted | Superseded by <link> / 相关文档链接
## 背景        （代码里看不出来的那部分：约束、历史、为什么会纠结）
## 决策        （现在定了什么，祈使句）
## 否决的备选  （表格：备选 | 否决原因）
## 后果与约束  （对后续开发的可执行要求，比如"字段必须 P0 落定"）
## 何时重新评估（触发推翻的信号；没有就写"无"）
```

写完后在 `docs/README.md` 目录约定下登记（新增文档必须同步）。判断标准：**三个月后有人问"为什么不用 ES"，答案在这个文件里，而不是靠记忆。**

---

## 7. 阶段收尾义务：每完成一个 Phase 写阶段总结

每个阶段（P0–P5，定义见设计文档 §14）**验收后**必须产出 `docs/reports/P<n>-<中文阶段名>-阶段总结.md`，由 AI 主动起草、用户定稿。它不是周报，是**下一阶段的输入**。

固定结构：

```markdown
# P<n> <阶段名> 阶段总结
- 日期 / 对应里程碑 / 实际耗时
## 1. 目标 vs 实际      （原计划逐条标 ✅ 完成 / ⚠️ 部分 / ❌ 未做 / 🔄 新增）
## 2. 本阶段产出的能力   （用户视角能做什么，不是类清单）
## 3. 验收证据          （命令 + 关键输出 + 截图路径；指标附脚本）
## 4. 与原设计的偏离     （偏离了什么、为什么、是否已回写设计文档 / ADR）
## 5. 已知缺陷与技术债   （带影响与优先级；每条要有"何时处理"的触发条件）
## 6. 遗留问题进入下一阶段（明确写出"这条必须在 P<n+1> 之前解决吗"）
## 7. 下一阶段入口条件   （可验证的门槛，比如"决策面板可用真数据演示 3 条规则"）
## 8. 借鉴使用记录       （本任务实际扫了哪些上游文件、用了什么机制、改了哪些、上游没有而自写的）
```

规则：

- 阶段验收通过但总结未写 → 视为阶段**未完成**，不得开始下一阶段。
- 总结里的"偏离"必须同步修正主文档（设计文档 / 结构文档），**以文档一致为完成**；不允许文档说 A 代码做 B。
- 验收证据必须是**真实跑过的命令输出**，不接受"应该没问题"。
- 一次性的踩坑不写总结，写进 commit body 或 ADR。

---

## 8. 常用命令

### 8.1 本机可跑（日常验证就是这些，Windows PowerShell；Java 21，JAVA_HOME=D:\jdk）

```bash
.\mvnw.cmd -q verify                                  # 编译 + 单测 + ArchUnit（默认排除 docker 组）
.\mvnw.cmd -q test -Dtest=MasteryCalculatorTest       # 单跑一个纯逻辑测试类（类名以实际代码为准）
.\mvnw.cmd -q -pl annona-server -am package            # 打包（P0-02 拆分模块后）
cd annona-web; pnpm install; pnpm typecheck; pnpm build; pnpm dev   # 前端（P0-09 后）
```

### 8.2 本机不跑（由 CI 或部署环境执行；不得因本机跑不了而删除或 mock 这些测试）

| 命令 / 活动 | 执行方 |
|---|---|
| `docker compose -f docker/docker-compose.yml up -d`、compose 冒烟 | CI compose job |
| `@Tag("docker")` 集成测试（Flyway 迁移、pgvector、`tsvector`/`pg_trgm`、Redis 限流） | CI services: postgres + redis |
| Playwright e2e、`scripts/rag-eval`、`scripts/bench` | CI（`e2e.yml` / `rag-eval.yml`）或定时 |
| `make quickstart` / `make up` / `make eval`（依赖 Docker） | CI 与用户环境；本机日常用 §8.1 原始命令 |
| `annona-cli` 的 `reindex` / `export` / `seed` | 部署环境或 CI（需真实数据库） |

### 8.3 本机确实要把应用跑起来时（P1 起才会需要）

自行安装 **Windows 原生 PostgreSQL 16**（含 `vector`、`citext` 扩展）与 **Redis**，参数写进 `.env`（模板 `.env.example`），再 `.\mvnw.cmd -pl annona-server spring-boot:run`。这是调试路径，**不是日常验证手段**，也不是把测试从 CI 搬回本机的理由。

### 8.4 借鉴扫描路径（开工前先查 `docs/annona-开发计划.md` §借鉴地图）

```text
🅖 D:\DEVELOP\interview-guide-master   后端 app\src\main\java\interview\guide\ · 前端 frontend\src\
🅜 D:\DEVELOP\java\MockPilot-project  后端 MockPilot-mian\AI-Meeting-main\admin\...\xunzhi\ · scripts\rag-eval · scripts\bench
🅢 D:\DEVELOP\summer-checkin-master    src\ · server\ · prisma\ · scripts\ · tests\
```

**提醒**：`mvn verify` 绿了不等于功能可用——本机跑不到中间件层，因此**PR 等 CI 绿了才算完成**；用户可见改动还要给出实际可复现的路径（请求序列或截图）。
