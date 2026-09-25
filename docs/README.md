# 年轮 · annona 文档总导航

> 你练过的每一分钟，都会长成下一道题的形状。

**第一次接触本项目？** 按这个顺序读：

```
annona-项目设计文档.md   →   annona-项目结构.md   →   annona-开发计划.md   →   architecture/overview.md
      (做什么)                  (放在哪)                 (何时做)                 (怎么连)
```

---

## 1. 三份主文档（阶段产物）

| 文档 | 阶段 | 回答的问题 | 状态 |
|---|---|---|---|
| [annona-项目设计文档.md](./annona-项目设计文档.md) | 阶段 1 · PRD | 做什么、给谁用、有哪些功能、领域模型与数据设计、技术选型、Non-goals、风险 | v1.0 定稿 |
| [annona-项目结构.md](./annona-项目结构.md) | 阶段 2 · 结构 | 仓库怎么摆、Maven 分几个模块、包边界与依赖方向、命名规范、ArchUnit 守门规则 | v1.0 定稿 |
| [annona-开发计划.md](./annona-开发计划.md) | 阶段 3 · 计划 | Phase 划分、任务清单、每项的验收标准与产出物、依赖顺序 | v1.0 定稿 |

主文档只放三份，超过三份说明该往 `architecture/` 或 `development/` 拆了。

**v1 之后的方向**：[plans/POST_V1_EXTENSION_PLAN.md](./plans/POST_V1_EXTENSION_PLAN.md) —— 扩展候选池、准入四问与拒绝清单。提新需求前先看这份，能省一轮讨论。

---

## 2. 目录约定

```text
docs/
├── README.md                     # 本文件：阅读地图 + 写作约定（新增文档必须在此登记）
├── annona-项目设计文档.md          # 阶段1：定位、功能全景、领域模型、决策层算法、技术选型
├── annona-项目结构.md             # 阶段2：目录树、模块与包职责、依赖方向、命名规范
├── annona-开发计划.md             # 阶段3：Phase、任务清单、验收标准
│
├── architecture/                 # 模块级架构设计，随代码维护（改代码就改这里）
│   ├── INDEX.md                  #   索引：模块 → 文档 → 负责人/状态
│   ├── overview.md               #   全局分层图、依赖方向、启动与请求生命周期
│   └── <module>.md               #   一个业务模块一篇（planner.md / retrieval.md / …）
│
├── specs/                        # 决策记录（ADR 风格，日期命名，写完不再改，只追加"后续修订"）
│   └── YYYY-MM-DD-<topic>-adr.md
│
├── plans/                        # 跨模块改造与演进计划（有始有终，完成即归档标注）
│   ├── POST_V1_EXTENSION_PLAN.md  #   v1 后期扩展候选池与拒绝清单（长期有效）
│   └── <TOPIC>_PLAN.md
│
├── reports/                      # 阶段总结：每完成一个 Phase 必须产出（见根目录 AGENTS.md §7）
│   └── P<n>-<中文阶段名>-阶段总结.md
│
├── development/                # 开发专题教程：按编号递增，讲"为什么这样写"而非 API 列表
│   └── NN-<主题>.md
│
├── tests/                      # 需人工实测的场景与指标验证（无法自动断言的部分）
│   └── 指标测试-<模块>.md
│
├── benchmarks/                 # 基准与评测报告（文件名含日期，便于纵向对比）
│   └── <主题>_YYYYMMDD.md
│
└── api/                        # 接口参考：由 SpringDoc 导出，脚本生成，不手写
    └── <module>.md
```

### 各目录的准入条件（防止文档坟场）

| 目录 | 什么时候写 | 什么时候不写 |
|---|---|---|
| `architecture/` | 模块进入实现期、且其内部结构无法从代码 5 分钟内读懂 | 代码还没动的模块（先写占位即视为债务） |
| `specs/` | 做出**代码里看不出来**的决策：选型、边界、取舍、否决项 | 常规实现细节、能从命名推断的东西 |
| `plans/` | 涉及 ≥3 个模块的重构或阶段性改造 | 单个功能开发（走开发计划任务项） |
| `reports/` | 一个 Phase 验收完成时（**强制**，未写视为阶段未完成） | 阶段进行中（进行中状态记在开发计划的任务勾选里） |
| `development/` | 同一个问题被新贡献者问了两次 | 一次性踩坑（写进 commit message 或 ADR 即可） |
| `tests/` | 指标需要真实环境或人眼判断（语音延迟、3D 帧率、报告可读性） | 能写断言的（进 `src/test`） |
| `benchmarks/` | 跑过 `scripts/bench` 或 `scripts/rag-eval` 出结果 | 没有脚本产出支撑的主观判断 |
| `api/` | `scripts/gen/` 导出快照，或 OpenAPI 无法表达的调用约定 | 手写接口清单（会立刻过期） |

### `specs/` 与 `plans/` 的区别

- `specs/` 记录**已经决定的事**（Decision / Consequences），写完即冻结。
- `plans/` 记录**将要改变的事**（步骤、影响面、回滚），执行完在文件头标注 `Status: Done (日期)`。

---

## 3. 写作约定

1. **一份文档一个主题**，标题下第一段必须写"这篇解决什么问题"。
2. **交叉引用用相对链接**，移动文件后要修链；禁止把同一内容复制到两处（改为链接）。
3. **表格优于长段落**：功能清单、字段定义、依赖关系一律表格。
4. **数字必须可复现**：性能、召回、延迟类结论必须附脚本路径与运行命令。
5. **过期文档不删，标注**：文件头加 `> ⚠️ Superseded by <link>`，保留决策脉络。
6. 图表用 Mermaid 或 ASCII，不用图片（diff 不友好）；`assets/` 仅在 README 截图需要时创建。
7. 新增/重命名文档 → 同步更新本 README 与 `architecture/INDEX.md`（视为同一次提交）。

## 4. 代码注释与文档的分工

| 内容 | 放哪 |
|---|---|
| 为什么这样实现、和谁有耦合 | 代码注释 + `architecture/<module>.md` |
| 模块职责与允许依赖 | `package-info.java`（每个包必有） |
| 结构约束的机器验证 | `annona-server/src/test/java/io/annona/arch/` ArchUnit |
| 跨模块流程与时序 | `architecture/overview.md` |
| 决策与被否决的备选 | `specs/*-adr.md` |
