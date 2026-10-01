# ADR: planner 决策内核的数据契约与接线方案

- 日期：2026-09-30 / 状态：Accepted
- 相关：[direction ADR 修订 3](./2026-09-25-direction-master-data-adr.md)（遗留义务：扩展 SignalSnapshot 方向维度）·[interview-session-adr](./2026-09-29-interview-session-adr.md)（组卷与冷热分层）·[evaluation-pipeline-adr](./2026-09-30-evaluation-pipeline-adr.md)（interview_report 四留痕）·设计文档 §6.1/§6.2/§6.4

## 背景

P1c 要在零实现的 `modules/planner` 上建决策内核：读信号 → 算掌握度 → 跑规则链 → 出组卷计划 → 落 trace。动手前全仓扫描发现四处"文档假设"与代码现实不符，且 SPI 现有形状不足以承载方向隔离：

- `SignalSnapshot` 是用户级聚合（无方向维度），无法表达"信号按 direction_id 隔离、方向相交才增强"——这是产品区别于「番茄钟 + 随机面试」的核心，direction ADR 修订 3 已把它列为 P1c-01 开工的**前置遗留义务**。
- `PlannedQuestion.directionKey` 挂着"命名待定稿"（direction ADR 修订 2 遗留），首个真实消费方就是本批。
- 设计文档 §5.3 列了 `mastery` 物化表与 `plan_task`/`todo_item` 表：前者是掌握度的猜测性持久化，后两者的模块（P2-06）根本还没建，页面是占位。
- `completionPercent` 的数据源是 plan_task，同样不存在。

## 决策

1. **SPI 契约修订 4**（`annona-spi`，尚无外部消费方，直接修订）：
   - `SignalSnapshot` 增加 `List<DirectionSignal> directionals` 与 `List<SessionOutcome> recentSessions`；`completionPercent` 保留但 P1c 期间恒 `null`（无数据源 ≠ 值为 0，面板据此不得把"无计划"渲染成"完成率 0%"）。
   - 新增 `DirectionSignal(directionId, sessions, avgScore, lastPracticedAt, verifiedStudyMinutes, selfReportedMinutes)`，自带 `hasStudyRecord()` / `studyOnlySelfReported()` 判定；VERIFIED+PARTIAL 归有效时长、SELF_REPORTED 单列（口径唯一权威 = study-collection-adr §决策 2）。
   - 新增 `SessionOutcome`：单场结果 + 四留痕（chatModel/evaluatorModel/promptHash/evaluatorVersion），取自 interview_report，掌握度事件与 VERSION_BASELINE 比对共用一份数据。
   - `LearningSignalReader` 加 `default readDirectional(userId, directionId, from, to)`——**用 default 返回零值快照**而非抽象方法：外部实现方升 spi 不被迫改代码，门面覆写即为真实行为；`read(userId,…)` 保留为全方向聚合。
   - `PlannedQuestion.directionKey` → `directionId`（收 direction ADR 修订 2 遗留）。
2. **门面与端口分层**：`shared/signal/SignalFacade` 实现 SPI `LearningSignalReader`；其数据经两个新 shared 只读端口 `shared/study/StudySignalPort`、`shared/evaluation/EvaluationSignalPort`，实现类各放属主模块（`modules/study/signal`、`modules/evaluation/signal`）——端口在 shared、实现在属主模块是 `InterviewEvalQueryService` 的既有先例，守住"shared 禁依赖 modules"。
3. **掌握度不建物化表**：`MasteryModel` 是事件序列的纯函数，组卷时现算，不落 `mastery` 表。
4. **接线通道零改主算法**：planner 输出 `PlanDecision(difficulties, reviewQuestionIds, traces)`；难度序列灌进 orchestrator 既有 `plan.InterviewPlan(totalCount, difficulties, followUpDepth)`；复习题靠 `pack(..., Set<UUID> reviewIds)` 新参在同难度桶内优先。
5. **guard 与规则链分两阶段**：`DecisionRule.apply` 契约不动（调整型规则专用：FORGETTING_CURVE / WEAK_DIRECTION / SAMPLE_GUARD-标记型）；SAMPLE_GUARD 保护语义、CAP_RATIO、VERSION_BASELINE 由 `planner/guard/GuardEngine` 前置判定，否决时用 rejectedBy 改写的 replaced trace，落库形态不变。
6. **失败降级不阻断主链路**：`CreateSessionRequest` 加可空 `planMode`（缺省 auto）；auto 下 advisor 抛异常 → catch + log.warn + 降级为原手动难度，skippedReasons 记"本次由默认策略出题"，会话照常创建；trace 表无人写即面板显示"本次无决策记录"（诚实呈现）。
7. **复习任务落点改 REMIND_REVIEW trace**：P1c-07 的"反哺 plan/todo"以 `decision_trace(action=REMIND_REVIEW)` 行呈现；真正回写 todo 待 P2-06 建表后接线。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 物化 `mastery` 表 + `InterviewEvaluated` 事件增量更新 | 要多一张表 + listener + 失效一致性；现算输入是有界小事件集（单方向 ≤10 场）且纯函数天然可复现（golden 免费），单人规模无性能压力 |
| 决策塞进 `QuestionPackService` 内部 | pack 是 85% golden 覆盖的无状态纯算法，混入 IO 与规则摧毁其行为规格属性、放大回归面 |
| `readDirectional` 设为抽象方法 | 破坏已发布 SPI 的后向兼容；default 零值让门面覆写即可 |
| 扩展 `plan_task`/`todo_item` 表承接复习任务 | 表与模块整体不存在，为单一提醒建全套 CRUD 是投机（YAGNI） |
| guard 改写 `DecisionRule` 签名加 veto 语义 | spi 是对外契约，为展示性 guard 改签名代价高，双阶段在 advisor 编排层消化即可 |
| `shared/signal` 直接 import study/evaluation 的 repository | 违背 shared/package-info "禁依赖 modules" 硬声明 |

## 后果与约束

- 契约修订必须在实现同一批次落 ADR（本文件），并回写 direction ADR 的遗留义务为"已履行"。
- `SignalSnapshot`/`InterviewPlan`(spi) 保持不可变（防御性拷贝），trace 的 rejectedBy 改写用新 record 替换、不原地改。
- planner 只能被 interview 调用（ArchUnit 规则"planner 禁依赖 interview/voice/schedule"）；`orchestrator → planner/advisor` 是白名单例外②。
- 掌握度公式常量（半衰期 H、k、lr、reviewRatio、capRatio、minSample、baselineSessions、window）全走 `PlannerProperties`，默认值唯一出处在 `application.yaml`（PropertiesDefaultSourceTest 机检）。

## 何时重新评估

- 单方向报告数 > 200 或组卷 P95 实测退化 → 重新评估掌握度是否需物化/缓存。
- P2-06 建 `plan_task`/`todo_item` 后 → REMIND_REVIEW 升级为真 todo 回写，`completionPercent` 接数据源。
- 出现真实用户困扰需区分"面试方向/自习方向"下拉 → 重新评估方向体系（见 direction ADR 同条）。
