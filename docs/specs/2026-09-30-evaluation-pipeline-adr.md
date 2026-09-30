# ADR: 面试评估链的数据模型、幂等复合语义与评估器版本

- 日期：2026-09-30
- 状态：Accepted
- 相关：[interview-session-adr](./2026-09-29-interview-session-adr.md)（`evaluator_version='v1'`
  幂等占位、交卷 fencing）、[metering-adr](./2026-09-29-llmprovider-metering-adr.md)（EVALUATION
  scene、evaluator 用途 Key、`UsageLedger` 端口）、[批 3 计划](../plans/P1B_BATCH3_EVALUATION_PLAN.md)

## 背景

批 2 交卷只落库不评分，`evaluator_version` 冻结为 `'v1'`（幂等键的一半），评估是"交卷即终点"。
批 3 要把交卷推进为"逐题评估 → 难度加权总分 → 可解释报告"。三处不易决定的点：① 评估是
**异步**链路（交卷后触发、状态可见），重投递/崩溃恢复要求幂等，而交卷侧已有自己的幂等键，两者
如何复合；② `evaluator_version` 只增不改值，评分器落定新值但不能改 'v1' 语义；③ 评分器 Key 的
消费通道——V10 表里有 `evaluator` 用途行，但全仓没有任何运行期读取路径（metering-adr 决策 1
"消费窄"把用户级 Key 路由推到托管议题），批 3 到底走哪条通道。数据量与延迟都不是瓶颈（单用户
自部署，一场面试 ≤ 数十题），所以取舍偏向**语义清晰与可解释**而非性能。

## 决策

1. **两张表各管一事**：`interview_evaluation`（逐题/逐追问评估明细）与 `interview_report`
   （会话级汇总 + 异步状态机）。报告页只轮询 `interview_report.status`，出口①"评估完成"的判据
   就是它变 `DONE`。否决"逐题行内嵌会话汇总"（每题×每版本行数失控，且 upsert 键形状不同）。
2. **幂等复合语义**：交卷幂等键 = `session_id + 'v1'`（fencing，批 2 已定，不改）；评估幂等键 =
   `interview_evaluation` 的 `UNIQUE (session_id, question_id, follow_up_index, evaluator_version)`，
   与 V9 `uq_answer_slot` 同构。消费者重投对同键走 upsert（`ON CONFLICT DO NOTHING`/更新既有行），
   DB 级兜底不双写。`interview_report` 的 `UNIQUE (session_id, evaluator_version)` 兼当评估运行的
   幂等锚（一 session 一版本一行）。
3. **evaluator_version='v2' 落定**：本批评分器写 `'v2'`（代码常量），消费只处理 COMPLETED 且
   session 版本为 `'v1'` 的会话并产出 `'v2'` 评估行；'v1' 语义（幂等占位）冻结不动。评分器升版
   = 新增版本号，不改历史行。
4. **evaluator Key 走 env 全局通道（修正计划假设）**：计划原写"V10 evaluator 用途 Key 经
   ModelProvider 消费→自动被 MeteredModelProvider 记账"。但 `llm_provider_config` 无任何运行期
   消费路径（metering-adr 决策 1 明确用户级 Key 路由不在本期）。**本批评分经 env 全局 chat 通道
   调用**（注入 `ModelProvider`，即 `@Primary MeteredModelProvider`），EVALUATION 场景记账天然
   覆盖（决策 3）；V10 的 `evaluator` 行维持"已备未消费"如实声明，与 KEK ADR 的 evaluator 预留
   同性质。真接用户级 evaluator Key 属网关语义变更 + KEK 轮换前置，届时另开 ADR。
5. **降级留原文（出口③）**：模型输出无法解析/畸形 → `fallback_used=true`、`raw_response` 保留
   逐题原文、`score=NULL`（宁缺勿假分）。汇总不因个别降级而中断。
6. **可比性四留痕放会话级 report**：`chat_model / evaluator_model / prompt_hash / evaluator_version`
   是一次评估运行的属性（不是每题各异），故落 `interview_report`；趋势断开的纯函数比较连续报告
   的四留痕（P1b-07）。逐题行只带 `evaluator_version`（幂等键成分）。
   6a. **prompt_hash 口径（收口批修订，2026-09-30 外审发现）**：= 四段评估 prompt（评分
   system/user 模板 + 汇总 system/user 模板）按固定顺序拼接的 SHA-256，只折静态模板文本、
   不折渲染后动态内容。初版只折评分 system 段——改汇总模板或 user 段评分指令不会断开趋势，
   与本决策意图（“评分提示模板变化”全覆盖）有缝。v2 尚无生产报告，修订无历史断链；
   后果：**prompt 变更后即使 `evaluator_version` 不升，哈希变化也会使趋势断开**——升版本
   不再是断开的唯一路径，这是特性不是事故。另：难度读不回时的缺省档固化为
   `ComparabilityRules.DEFAULT_DIFFICULTY = 3`（域中值，不往任一侧偏）。
7. **异步链路**：交卷赢者分支 `afterCommit` 投 `interview_report` PENDING + Redis Stream 消息
   （复用 `TaskStreamPort` + `QuestionGenStream` 形态，非 interview-guide 的 `AbstractStreamProducer`），
   消费在 `modules/evaluation`。评估经 `StructuredOutputInvoker`（重试预算由 T-demo 实测服从率定）。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 逐题评估行内嵌会话级汇总列 | 每题×每版本行数失控；汇总与明细的 upsert 键形状不同，混在一张表两头不讨好 |
| `question_id NULL` 哨兵行表会话汇总 | NULL 双语义破坏可解释（同一列既指题目又指"整场"），两张表零额外成本 |
| 评估器消费 V10 evaluator BYOK Key | 全仓无运行期消费路径；接它 = 提前做用户级 Key 路由（网关语义变更 + KEK 轮换前置），塞进评估批次两头做不好 |
| 交卷即同步评分 | LLM 调用进交卷请求路径，违反"LLM 不进事务/同步慢"；且无法承载崩溃恢复 |
| `evaluator_version` 复用改 'v1' 语义 | 破坏批 2 幂等契约与历史行可比性；只增不改是钉死约束 |

## 后果与约束

- 评估表迁移为 **V13**（V12 被 metering 的 scene CHECK 扩展占用，见 metering-adr 批 3 修订 7）；
  已应用迁移冻结禁改。
- `interview_evaluation`/`interview_report` 必须登记进 `FlywayBaselineIT` 全库清单（pre-commit
  `check-migration-inventory.py` 机检）；两表幂等键/状态 CHECK 由 `MigrationShapeIT` 真库断言。
- 消费写走 `@Modifying` upsert 的必须包活动事务（`check-modifying-callers.py` 机检 + docker-it 兜底）。
- 预置主键实体（`UUID.randomUUID()`）注意 save/merge 语义：需要回读 DB-default 列时接住返回值再 refresh。

## 何时重新评估

- 用户级 evaluator Key 路由落地时（改决策 4，随网关 ADR）。
- 单场面试题目数增长到逐题串行评估延迟不可接受时（改消费并发/批量策略）。
- prompt 模板需要大改（rubric 口径变化）时：`evaluator_version` 递增 + `prompt_hash` 留痕已备，
  趋势会按可比性判定自动断开，无需改表。
