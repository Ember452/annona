# CLAUDE.md

**本文件只做一件事：指向 [`AGENTS.md`](./AGENTS.md)。** 规范正文一律不在此复制——同一内容写两处必然漂移（`AGENTS.md` §2「同一内容不复制到两处」）。

@AGENTS.md

## 使用说明

- **唯一真相源是 `AGENTS.md`**：绝对规则、代码质量约束、注释规范、借鉴扫描义务、commit / PR 规范、ADR 义务、阶段收尾义务、常用命令，全部在那里。本文件与它冲突时，以 `AGENTS.md` 为准。
- **要改规则就改 `AGENTS.md`**，不要在这里新增或覆盖条款；否则不同入口（人、Claude、其它 agent）读到的规范会分叉，而这正是本文件只写引用、不写正文的原因。
- 开工前按 `AGENTS.md` §2 文档地图定位、按 §4 先做借鉴扫描（未扫描 = 任务未开始）；本机无 Docker，日常验证是 §8.1 的 `.\mvnw.cmd -B -q verify` 与 `pnpm typecheck` / `pnpm build`，容器类测试交 CI，**PR 等 CI 绿了才算完成**。
