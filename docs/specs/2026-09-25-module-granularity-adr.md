# ADR: Maven 按依赖层次切 5 个模块，业务模块以包形式共存

- 日期：2026-09-25
- 状态：Accepted
- 相关：[../annona-项目结构.md](../annona-项目结构.md) §3

## 背景

annona 有 16 个业务模块（identity/study/plan/knowledge/retrieval/qa/questionbank/interview/evaluation/planner/voice/schedule/agent/usage/resume/notify）加一层 `shared` 内核。物理结构有两种极端：单 Maven 模块（边界靠自觉），或每个业务模块一个 jar（大厂多模块式）。

## 决策

**5 个 Maven 模块**：`annona-common`、`annona-spi`、`annona-infrastructure`、`annona-server`（含全部业务模块的包）、`annona-web`。

- 只在"编译期能强制约束"处切一刀：业务代码依赖 `annona-spi` 接口，`annona-infrastructure` 提供实现并在**运行期**装配（`server` 的 pom 里 infra 是 runtime 依赖）。
- 业务模块之间的边界用 ArchUnit 在 CI 强制，不用 pom 强制。
- 唯一同步跨模块调用白名单：`interview/orchestrator → planner/advisor`。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 单 Maven 模块（Initializr 现状） | 业务与中间件实现之间没有任何编译期屏障，"Service 直接 import ES SDK"这类事一定发生 |
| 16 个业务 jar（每模块一个） | 只有一个可部署产物，无独立发布需求；跨模块调用会立刻遇到循环依赖，解法通常是往 `common` 塞业务代码，`common` 变垃圾场；改一个功能要动 3+ 个 pom |
| COLA 四层（adapter/app/domain/infrastructure/client） | 按层垂直切与"模块自包含、可整体剥离"的产品主张相反；单人项目下 `client` 与 `domain` 会退化成转发层 |
| Spring Modulith | 方向正确（就是为这个问题设计的），但引入新框架的版本风险与学习成本高于收益；其"模块间事件 + 只读查询"约定已吸收进本设计的跨模块规则 |

## 后果与约束

1. `annona-spi` 禁止依赖 Spring / Persistence / 任何 SDK，否则外部实现方无法轻量接入（ArchUnit 规则覆盖）。
2. 领域事件与跨模块读模型必须放 `io.annona.shared.*`，不允许在业务模块包里定义给别的模块消费的 DTO。
3. 版本全部集中在根 `dependencyManagement`，子模块 `<dependency>` 不写 version。
4. 新增业务模块 = 在 `annona-server` 内新增包 + ArchUnit 白名单登记，不动 pom。

## 何时重新评估

- 出现第二个可部署产物（如独立的向量化 worker、独立的语音网关）时，为其新建 Maven 模块，而不是继续加包。
- 单包内类数量超过 ~60 且频繁合并冲突时，考虑把该模块拆为独立 jar。
