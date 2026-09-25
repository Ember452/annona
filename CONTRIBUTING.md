# 参与贡献

先说清一件事：**这个仓库的规则文档偏多，是为了让人和 AI 都能在零上下文时接手**。所以本文件不重复规则，只给路径。

## 动手前按顺序读三样

1. [`AGENTS.md`](AGENTS.md) —— 硬规则（§0）、代码质量与测试门槛（§4）、commit 规范（§5）、文档义务（§6/§7）。
2. [`README.md`](README.md) 的**当前仓库状态表** —— 现在到哪一步、哪些命令真能跑。
3. [`docs/annona-开发计划.md`](docs/annona-开发计划.md) —— 顶部「当前进度」表定位阶段；你要做的任务在阶段表里找 ID（如 `P1a-07`）。

## 环境准备

| 需要 | 说明 |
|---|---|
| JDK 21 | 仓库用 Maven Wrapper，**不必自己装 Maven**（`.\mvnw.cmd` / `./mvnw`） |
| Node + pnpm | 前端在 `annona-web/`（P0-09 之后存在） |
| ~~Docker~~ | **开发期不需要，也不要跑 Docker 相关命令与容器测试**。本机验证只有：`mvn -q verify`（编译 + 单测 + slice + ArchUnit）与前端 `typecheck/build`。原因与代价见 [`docs/specs/2026-09-25-dockerless-local-dev-adr.md`](docs/specs/2026-09-25-dockerless-local-dev-adr.md) |
| PostgreSQL / Redis | 只有当你需要把应用真跑起来看界面时才准备（P1 起才有这需求）；CI 上由 `services:` 提供 |

## 第一个 PR 的流程

1. **先开 issue 讨论再动手**（缺陷报告、功能请求、新面试方向提案）。注意：**本项目自身的施工任务不建 issue**，任务清单在 [`docs/annona-开发计划.md`](docs/annona-开发计划.md)，对外进度以一个阶段一个 issue 展示；想认领请看 `good-first-issue` 标签（P2 之后投放）。改动涉及数据模型、模块边界、决策算法、新依赖时，issue 里先给方案，等维护者确认。
2. **借鉴扫描**：任务在开发计划「借鉴地图」里有对应路径的，**先读那些上游文件**，并在 commit 正文留下四行借鉴说明（读过什么 / 借鉴哪个机制 / 必须改什么 / 上游没有的部分）。未扫描不算开工。
3. 分支：`feat/<scope>-<简述>` 或 `fix/<scope>-<简述>`，从 `main` 切。
4. 提交：**英文 Conventional Commits**，一个 commit 一个逻辑变更，正文带 `Task: P<n>-<序号>`（对应开发计划的任务 ID），签名带 DCO（`git commit -s`）。细则与反例见 [`AGENTS.md` §5](AGENTS.md)。
5. **绝不提交任何真实 Key**；`.env` 已被忽略，pre-commit 也有 gitleaks（禁止 `--no-verify`）。
6. 开 PR：描述里写"改了什么 / 为什么 / 如何验证（贴命令与输出）/ 是否影响决策输入或检索指标"。**本机跑不到的部分由 CI 验证，PR 必须等 CI 绿。**（维护者本人在 P0 阶段可直推 `main`，但从第一次接受外部贡献起一律走 PR。）

## 各类贡献的确切落点

| 你想做的 | 改哪里 | 是否需要 ADR |
|---|---|---|
| 新增一个面试方向 | `annona-server/src/main/resources/skills/<key>/SKILL.md`（front-matter 必须含 `key/name/parent/difficultyCurve/stages/evaluationFocus`） | 否 |
| 修某个业务缺陷 | `annona-server/.../modules/<name>/` | 否（但要附能复现的失败测试） |
| 新的模型 Provider | `annona-infrastructure` 的 `llm` 包 + 注册配置 | 视是否引入新依赖 |
| 新的检索后端 / 身份源 | **建议放在你自己的仓库**，依赖 `io.annona:annona-spi` 实现接口 | 不进主仓 |
| 改决策层算法、数据模型、包边界、依赖版本策略 | 对应模块 + Flyway | **是**（先在 `docs/specs/` 提一条 ADR） |

完整目录职责见 [`docs/annona-项目结构.md`](docs/annona-项目结构.md)；模块内部设计见 `docs/architecture/`（索引 `INDEX.md` 说明了哪些模块**不需要**文档）。

## 测试与文档要求（会被 CI 拦）

- 纯逻辑单测优先：算法、状态机、规则链、边界值都要能在**没有数据库**的情况下断言。
- 关键包（`planner/mastery`、`planner/guard`、`evaluation/structured`、`knowledge/chunk`）覆盖率 85%，其余 60%（从 P1a 起生效）。
- 需要真实中间件的测试打 `@Tag("docker")`，本机默认排除、CI 专跑。
- 结构约束由 ArchUnit 强制；想开例外只能先提 ADR 再改白名单。
- 每完成一个阶段，必须写 `docs/reports/P<n>-<名>-阶段总结.md`（模板见 AGENTS.md §7）。

## 许可证与授权

- 代码贡献以 **AGPL-3.0** 提交，采用 **DCO**（`git commit -s`），不要求签 CLA。
- `skills/` 目录下的内置 `SKILL.md` 内容采用 **CC-BY-4.0**，便于站外引用与社区改写。
- 本仓库参考了若干开源项目的能力与设计，但**不搬运代码**；实质性引用会在文件头注明来源并保留许可证信息。

## 行为准则

提 issue、PR、讨论时请遵守 [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)。简单说：**对事严格，对人客气**；批评指向代码，不指向人。
