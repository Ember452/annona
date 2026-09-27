# ADR: 前端测试/lint 栈采用 vitest + ESLint,后端覆盖率以 JaCoCo 机检

- 日期：2026-09-27
- 状态：Accepted
- 相关：[开发计划](../annona-开发计划.md) §风险登记（JDK/升级窗口）、P0 阶段总结 §5 D18（假绿教训）、代码：`annona-web/{package.json,eslint.config.js,vite.config.ts}`、根 `pom.xml`（jacoco/surefire argLine）、`annona-server/pom.xml`（coverage-check）、`scripts/ci/validate-workflows.py`

## 背景

番茄钟到期转移 bug（状态机在 focus/break 间每 250ms 振荡、休息段整段被吞）是**过期闭包**类缺陷：后端 slice 测试探不到，前端既无测试也无 lint——代码里早已写着 `eslint-disable` 注释，但仓库根本没装 ESLint，门禁空转。同时 AGENTS §4 的覆盖率承诺（其余 60%、关键纯逻辑包 85%）"从 P1a 起生效"，却没有任何工具对账。本批（P1a-04 后的质量机检批）一次性补齐三件事：前端测试、前端 lint、后端覆盖率机检。

## 决策

1. **前端测试 = vitest 3.x + jsdom + @testing-library/react 16**；test 配置并入 `vite.config.ts`（`defineConfig` 取自 `vitest/config`，一份配置同时喂 build 与 test），`pnpm test` 进 CI。
2. **前端 lint = eslint 10 平面配置 + typescript-eslint 8 recommended + eslint-plugin-react-hooks**（`rules-of-hooks` 与 `exhaustive-deps` 均为 error），`pnpm lint` 进 CI。
3. **后端覆盖率 = jacoco-maven-plugin 0.8.12**：prepare-agent/report 挂根 build（全模块继承），`check` 只挂 `annona-server`——BUNDLE 行覆盖 ≥ 60%（实测基线 68.0%，2026-09-27，unit+slice 口径）；surefire argLine 用 `@{jacoco.argLine}` 运行期求值。
4. 关键纯逻辑包 85%（planner/mastery、planner/guard、evaluation/structured、knowledge/chunk、infra 分词与向量序列化）**不预置规则**：各包首个落地时（P1a-06 起）在 server pom 追加带 `<includes>` 的专用 check execution。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| jest + ts-jest/babel | vitest 与 vite 共享 transform 管线零额外配置；jest 在 Vite 项目要维护第二套 transform 链 |
| vitest 5（最新 major） | peer 要求 vite 6/7，项目锁 vite 5.4——强升 vite 是另一个批次的事；锁 vitest 3.2.7 是兼容解（实测 vitest 5 + vite 5 启动即 `ERR_PACKAGE_PATH_NOT_EXPORTED`） |
| 保留 eslint-disable 注释但继续不装工具 / 只靠 review | 番茄钟 bug 即反例：两行 disable 注释存在而无人检查，防线的存在感是假的 |
| 覆盖率阈值只写在文档里 | 承诺与事实无机器对账，等于 D18 假绿的温柔版；jacoco `check` 让跌破 60% 直接构建红 |
| check 挂全部 4 个 Maven 模块 | common/spi/infra 自身模块内的测试覆盖不了"被 server 消费"的代码，按模块隔离统计会误伤；聚合口径（report-aggregate）在 P1a 收尾评估 |
| 引入 Prettier / checkstyle | `.editorconfig` + AI 协作已保持格式一致，引入即全量 churn，违反 AGENTS §3.3 |

## 后果与约束

1. CI frontend job 步骤为 typecheck → lint → test → build；`mvn verify` 含覆盖率 check。**不许**为过线排除有逻辑的类——排除只允许 dto/entity/*Properties 这类无逻辑样板，且必须在 pom 注释写明理由。
2. vitest 3 与 vite 5 绑定：**升级 vite（6/7）时必须同步升 vitest** 并回归全部前端测试（与开发计划 §风险登记的 JDK/Boot 升级行同一批次处理最省）。
3. 前端导出的 hook 必配 vitest 测试成为 DoD 惯例（AGENTS §8.1 命令行已含 lint/test）。
4. AGENTS §4 的"约定→机检元规则"是本 ADR 的上位原则：机检缺位的约定都按该规则补齐。

## 何时重新评估

- vite 升级 6/7 时：解除 vitest 版本压制，重估 vitest 5。
- 外部前端贡献者成规模：重估 Prettier / CI 格式门禁。
- P1a 收尾：评估 report-aggregate 聚合报告，决定是否把 60% 底线扩到 common/infrastructure。
