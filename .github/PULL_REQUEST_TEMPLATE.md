<!--
标题按 Conventional Commits：`type(scope): 一句英文 subject ≤72 chars`。
type 与 scope 参考 AGENTS.md §5。
-->

## What

一句话说这个 PR **做了什么**（不是"为什么"，那是下一段）。文件级、模块级的变更清单，
bullet 列出，每条一到两行。

- ...
- ...

## Why

**为什么**要做这件事。若是修 bug，link `Fixes #NNN`；若是提案功能，link `Closes #NNN`；
若命中某个 ADR，link ADR 路径。

## How to verify

**贴命令与关键输出**。AGENTS.md §3.4 "Goal-Driven Execution" 要求每个"改了什么"都
配一个能证伪它的命令。

```powershell
# 例
.\mvnw.cmd -B -q verify
# EXIT=0
# Surefire: tests=34 failures=0 errors=0 skipped=0
```

前端相关：

```powershell
cd annona-web
pnpm typecheck
pnpm build
```

## Impact on decision inputs or retrieval metrics

**必答**。三选一，勾上并展开。

- [ ] 无影响（纯重构、纯 UI 修、纯文档）
- [ ] 影响决策输入：改了 planner / mastery / guard / signal 的算法或输入装配
  - 说明改了哪条规则 / 哪个信号字段；是否已写 ADR；面板文案是否需要同步
- [ ] 影响检索指标：改了 retrieval / knowledge / chunk 的策略
  - 说明改动前后 `scripts/rag-eval` 的 Recall@K / MRR 数字，或注明"待 P1a-09 首跑基线"

## ADR / 文档同步

- [ ] 无 ADR 需要写（改动不涉及数据模型 / 决策算法 / 依赖引入 / 性能取舍 / 许可 / 密钥处理）
- [ ] 有 ADR，路径 `docs/specs/YYYY-MM-DD-<topic>-adr.md`，本 PR 已含
- [ ] 更新了 `docs/annona-项目设计文档.md` / `docs/annona-项目结构.md` / `docs/annona-开发计划.md` 的：___

## Task 编号

`Task: P0-XX` / `P1a-XX` / `P<n><子>-<序号>` 在**每条** commit 的 body 里。PR 层面
link 到对应阶段 issue 或本 PR 覆盖的任务号。

## Checklist（提交前自查）

- [ ] `./mvnw.cmd -B -q verify` 本地全绿（含 ArchUnit 七条与所有 unit / slice 测试）
- [ ] `cd annona-web && pnpm typecheck && pnpm build` 本地全绿（若动了前端）
- [ ] 无新增 `Executors.newXxx*` 调用（AGENTS.md §0 + ArchUnit 规则 7）
- [ ] 无新增硬编码 API Key / 密码 / Token（gitleaks pre-commit + CI job 都会拦）
- [ ] 未在事务里调 LLM / S3 / 外部 HTTP（AGENTS.md §0.3）
- [ ] Entity 未出现在 controller 出入参（ArchUnit 规则 4）
- [ ] 每个 commit 是完整单一逻辑变更（AGENTS.md §5），无跨模块混改
- [ ] 每条 commit 已带 `Signed-off-by`（`git commit -s`，DCO 强制，见 docs/annona-项目结构.md §13.4）
- [ ] 涉及公共能力（`annona-common/` 或 `annona-spi/`）改动已在 PR 描述里明列**兼容性影响**
- [ ] 若命中 [AGENTS.md §6 触发清单](../blob/main/AGENTS.md)，ADR 已随本 PR 一起进（不许"先合代码再补 ADR"）
