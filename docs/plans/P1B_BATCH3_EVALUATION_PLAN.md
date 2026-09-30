# P1b 批 3：评估链（评分 / 可比性 / 报告 / 简历）实施计划

> **For agentic workers**：与批 2 同流程执行（executing-plans、逐 checkpoint 请示 commit）。
> **状态：草案，待用户定稿**——尤其"范围裁剪"与"排期"两节。

**Goal:** 把批 2 留下的"交卷即终点"推进为"交卷 → 逐题评估 → 加权总分 → 可解释报告（PDF）"，并闭合 P1b 出口 ①③④⑥。

**Spec/输入**：[批 2 收口小结](../reports/P1b-批2-面试链-收口小结.md) §5/§7、
[interview-session-adr](../specs/2026-09-29-interview-session-adr.md)（`evaluator_version='v1'`
消费契约）、[metering-adr](../specs/2026-09-29-llmprovider-metering-adr.md) 决策 4（流式计量缺口）。

## Global Constraints（继承批 2，新增两条）

- LLM 调用不进 DB 事务；评估是**异步**链路（交卷后触发，状态可见）。
- `evaluator_version` 只增不改值；'v1' 语义冻结为"批 2 幂等占位"，本批评分器落定新值（'v2' 起）。
- 新数据模型/Flyway 变更 → ADR 触发项（本批预计 V12 `evaluation` 表 + 可能 V13 usage 索引）。
- 批 2 的六颗雷教训固化：新表登记走门禁；**每个 @Modifying/异步写自查事务覆盖**；
  IT 夹具必造 app_user 根行；并发用例不引入无发令的 latch；jsonb 映射必带 `@JdbcTypeCode`。

## 范围与任务（按依赖排序）

| # | 任务 | 要点 | 验证（可证伪） |
|---|---|---|---|
| T0 | **SPI usage 契约扩展**（qa 流式 + embed 计量补票，metering-adr 决策 4 的债） | `StreamingChatProvider` 完成回调增 usage 参数、`EmbeddingProvider.embed` 返回带 usage 的结果类型；spi 对外契约变更 → 独立 ADR 小节 | 一次真实 qa 问答后 `GET /api/usage/session/{qaSessionId}` 非零；决策 4 撤"未计量"声明同步改面板文案 |
| T1 | **P1b-06 evaluation 核心**：`modules/evaluation` 分批评估 | 消费 finalize 结果（afterCommit 投 Redis Stream，复用 AbstractStreamProducer）；逐题批 + 二次汇总走 `StructuredOutputInvoker`；`evaluator` 用途 Key（V10 已备）经 ModelProvider 消费→**自动被 MeteredModelProvider 记账**；降级 `fallback_used` 标记 | slice：3 种畸形输出构造（非 JSON/缺字段/超长）均落 fallback 且逐题原文保留（出口③）；docker IT：交卷→评估完成→分数可见全链 |
| T2 | 评估数据模型 | V12 `interview_evaluation`（session_id、question_id、follow_up_index、score、feedback、strengths/improvements JSONB、fallback_used、model/evaluator_version 留痕）；`AnswerEvaluationService` 形态借 🅖 | FlywayBaselineIT 登记（门禁自动守）；save/merge 语义复查 |
| T3 | **P1b-07 可比性** | 总分=难度加权（权重进 PackRules 同款常量类+注释依据）；`chat_model/evaluator_model/prompt_hash/evaluator_version` 四留痕消费；可比区间判定纯函数 + golden | 单测：换 evaluator_version 后趋势断开的**行为规格**（出口④）；golden 快照 |
| T4 | 前端评估报告页 | 交卷页从占位改为轮询评估状态→报告视图（雷达图用现成依赖？无则 SVG 手绘，不引新库——引入即 ADR） | 四门 + 人工 demo 截图 |
| T5 | **P1b-08 简历模块**（独立，可并行/后置） | 上传→Tika 解析→Redis Stream 异步 AI 分析→重复检测；`resume` 模块自包含 | slice 解析失败路径；docker IT 异步链 |
| T6 | **P1b-09 PDF 导出** | iText + 内置中文字体（字体文件入仓许可核一遍）；异步导出复用 TaskStreamPort | 人工比对 PDF 与页面数据一致、中文不乱码（出口①后半） |
| T7 | 收口 | 出口⑥阶段总结（§7 模板，输入=批 2/3 两份批次小结）；开发计划进度回写 | 六出口逐条对凭证 |

**建议排期**：T0 0.5d / T1 3d / T2 1d / T3 2d / T4 1.5d / T5 1.5d / T6 1.5d / T7 0.5d ≈ **11.5d**；
若批 3 想瘦身：**T5/T6 可整体推批 4**（出口①只要求"完成一场面试"，PDF 是①的增强项——需你确认口径后再定）。

## Non-goals（本批不做）

评分实时流式推送（轮询够用）；用户级用量面板的全家桶（只补 T0 的会话钻取）；
`@RateLimit` 注解骨架；PlannedQuestion 改名（P1c-01 前不动）；徽章/群聊等既有 non-goals。

## 风险（如实）

1. **prompt 质量无基线**：批 2 demo 若发现 rubric 成段率低（出题链首跑还没做），T1 的评估质量会被同一个未知拖累——**先做批 2 demo，拿到 JSON 服从率数据再定 T1 的 retry 预算**；
2. 异步评估链是仓内第一条"业务事件→Stream→消费写库"的完整闭环，重投/幂等语义要在 T1 的 ADR 里定（交卷幂等键与评估幂等键如何复合）；
3. iText AGPL 与字体许可属合规触发项（§6 许可条目），T6 前置核查。
