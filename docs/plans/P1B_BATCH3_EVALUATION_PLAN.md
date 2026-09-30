# P1b 批 3：评估链（评分 / 可比性 / 报告）实施计划

> **For agentic workers**：与批 2 同流程执行（executing-plans、逐 checkpoint 请示 commit）；**分支修复权单点归属**（批 2 收口小结 §4 的教训固化——另一方只审不改，勿重做已定位的修复）。
> **状态：已定稿（2026-09-30）**。定稿解决的四个问题：① 出口①含"导出报告"（开发计划 P1b 出口原文），T6 是①的本体成分**不可外推**，T5 推批 4（原草案"增强项"的误读已修正）；② T0 的 SPI 变更方式定为**直接断**（理由见 Global Constraints）；③ 批 2 §3 的"@Modifying 调用点机检"从待议升级为**同批落地**（AGENTS §4 元规则）；④ 雷达图定案 **SVG 手绘**（已查实 package.json 无图表库、P0-09 明确排除 recharts，执行期不再现场决策）。

**Goal:** 把批 2 留下的"交卷即终点"推进为"交卷 → 逐题评估 → 加权总分 → 可解释报告（PDF）"，使 P1b 出口 **①③④** 具备凭证（②⑤ 批 2 已闭、收口仅核对引用；⑥ 阶段总结随批 4）。

**Spec/输入**：[批 2 收口小结](../reports/P1b-批2-面试链-收口小结.md) §5/§7、
[interview-session-adr](../specs/2026-09-29-interview-session-adr.md)（`evaluator_version='v1'`
消费契约）、[metering-adr](../specs/2026-09-29-llmprovider-metering-adr.md) 决策 4（流式计量缺口）、
[技术债排期](../annona-开发计划.md#技术债排期2026-09-30-全仓质量扫描)（TD-02/03/04/12 已折入本批对应任务）。

## Global Constraints（继承批 2，定稿增改见下）

- LLM 调用不进 DB 事务；评估是**异步**链路（交卷后触发，状态可见）。
- `evaluator_version` 只增不改值；'v1' 语义冻结为"批 2 幂等占位"，本批评分器落定新值（'v2' 起）。
- 新数据模型/Flyway 变更 → ADR 触发项（本批预计 V12 `evaluation` 表 + 可能 V13 usage 索引）。
- 批 2 的六颗雷教训固化：新表登记走门禁；IT 夹具必造 app_user 根行；并发用例不引入无发令的 latch；jsonb 映射必带 `@JdbcTypeCode`。
- **@Modifying/异步写调用点机检同批落地**（批 2 §3 原登记为"待议"，按 AGENTS §4"踩坑一次、同批次配机检"升级，不再待议）：T1 首个 checkpoint 定机制（ArchUnit 规则或 CI 脚本），验收可证伪——**门禁带自测，故意写一个无事务 @Modifying 调用点必须红**（`check-migration-inventory.py` 自测先例）。
- **SPI 破坏性变更纪律**：`EmbeddingProvider.embed` 返回类型变更走**直接断**，不加 default 方法桥——旧签名承载不了 usage 数据、桥只能 deprecated+delegate，而实现方全在仓内（fake + OpenAiCompatible 两处），桥无受益者；spi 处于 0.x 线，semver 允许。ADR 记三件：版本线、实现方清单、否决 default 桥的理由。

## 范围与任务（按依赖排序）

| # | 任务 | 要点 | 验证（可证伪） |
|---|---|---|---|
| T-demo | **开工门槛（批 2 遗产，不计入本批人日，用户执行）**：批 2 链 10 步人工 demo + JSON 服从率测量 | 凭证截图回写 #17；服从率数字进 T1 ADR 定 retry 预算；**顺手完成 T6 的字体许可核查**（见风险 3） | #17 有 10 步凭证；T1 ADR 引用实测服从率 |
| T0 | **SPI usage 契约扩展**（qa 流式 + embed 计量补票，metering-adr 决策 4 的债）——**直接断**，见约束 | `StreamingChatProvider` 完成回调增 usage 参数、`EmbeddingProvider.embed` 返回带 usage 的结果类型；**折入 TD-03**（`token_usage.provider` 改记 provider 身份，修正与 model 恒同值的归因失真）、**TD-04**（`EMITTER_TIMEOUT_MS` 改为 2×`annona.model.chat.timeout-seconds` 派生）、**TD-02 顺手**（动 embed 调用点时把 KnowledgeVectorizeService 逐行事务并进批次事务）；spi 对外契约变更 → ADR 独立小节 | 一次真实 qa 问答后 `GET /api/usage/session/{qaSessionId}` 非零且 provider 列语义独立于 model；决策 4 撤"未计量"声明并同步改面板文案 |
| T1 | **P1b-06 evaluation 核心**：`modules/evaluation` 分批评估 | 消费 finalize 结果（afterCommit 投 Redis Stream）；逐题批 + 二次汇总走 `StructuredOutputInvoker`；**retry 预算由 T-demo 实测服从率定，不猜**；`evaluator` 用途 Key（V10 已备）经 ModelProvider 消费 → 自动被 MeteredModelProvider 记账；降级 `fallback_used` 标记；**首个 checkpoint = @Modifying 机检落地** | slice：3 种畸形输出构造（非 JSON/缺字段/超长）均落 fallback 且逐题原文保留（出口③）；docker IT：交卷→评估完成→分数可见全链 |
| T2 | 评估数据模型 | V12 `interview_evaluation`（session_id、question_id、follow_up_index、score、feedback、strengths/improvements JSONB、fallback_used、model/evaluator_version 留痕）；唯一键 **(session_id, question_id, follow_up_index, evaluator_version)**、重投 = upsert（与 V9 `uq_answer_slot` 同构，T1 ADR 记录复合幂等语义）；`AnswerEvaluationService` 形态借 🅖 | FlywayBaselineIT 登记（门禁自动守）；save/merge 语义复查 |
| T3 | **P1b-07 可比性** | 总分=难度加权（权重进 PackRules 同款常量类+注释依据）；`chat_model/evaluator_model/prompt_hash/evaluator_version` 四留痕消费；可比区间判定纯函数 + golden | 单测：换 evaluator_version 后趋势断开的**行为规格**（出口④）；golden 快照 |
| T4 | 前端评估报告页 | 交卷页从占位改为轮询评估状态→报告视图；**雷达图 SVG 手绘**（已定案，无图表依赖、不引新库——引入即 ADR） | 四门 + 人工 demo 截图 |
| T6 | **P1b-09 PDF 导出（仅评估报告）**——出口①的本体成分 | iText + 内置中文字体；异步导出复用 TaskStreamPort；简历报告半边随 T5 外推批 4 | 人工比对 PDF 与页面数据一致、中文不乱码（出口①后半） |
| T7 | 收口 | **折入 TD-12 配置单源化**（MAX_RETRY×2、QuestionGenRecoveryScheduler 阈值、require-kek 默认值 4 处）；六出口凭证核对——**② 仅核对引用批 2 凭证**（docker IT 双线程幂等实证）、**⑤ 以 T0 补票后的钻取数据核对**（主体凭证批 2 已出），①③④ 新取证——**① 的端到端新凭证 = 批 3 收口时重跑 demo 的评估与导出段（交卷→评估→PDF）**：T-demo 只覆盖批 2 链，缺这次重跑则①的凭证归属不清；开发计划进度回写；**P1b 阶段总结不在本批**——批 4 验收后写（修正批 2 收口小结 §4 的"批 3 验收后"时序表述） | 六出口逐条对凭证；TD-12 单源化有 diff |

**已外推至批 4（简历与交付）**：T5（P1b-08 简历模块——与六出口无一挂钩，①的"报告"是面试评估报告）+ T6 的简历报告半边 + P1b 阶段总结。

**建议排期**：T0 0.5d / T1 3d / T2 1d / T3 2d / T4 1.5d / T6 1.5d / T7 0.5d ≈ **10d**（T-demo 为门槛不计入）。与阶段 issue #17 按任务表余量估的 6.5d 差 3.5d，可指认：T0 0.5d 为 P1b-06/07 预算外的新增范围（metering-adr 决策 4 补票）；T1 3d vs P1b-06 2d 按批 2"加固 ≈ 实现 50%"经验上调；T2/T4/T7 为批次整循环开销。**定稿动作：#17 批次表同步为"批 3 评估链 ≈10d / 批 4 简历与交付 ≈3d（T5 1.5d + T6 简历半边 ≈0.5d + P1b 阶段总结 ≈1d）"**（同步文本已备，见 issue 操作）。

## Non-goals（本批不做）

评分实时流式推送（轮询够用）；用户级用量面板的全家桶（只补 T0 的会话钻取）；
`@RateLimit` 注解骨架；PlannedQuestion 改名（P1c-01 前不动）；**简历模块（推批 4）**；徽章/群聊等既有 non-goals。

## 风险（如实）

1. ~~prompt 质量无基线~~ → **已转为 T-demo 门槛**：服从率实测先于 T1，retry 预算不再靠猜；若 demo 显示 rubric 成段率低，T1 排期按比例上调并在 #17 同步。
2. 异步评估链是仓内第一条"业务事件→Stream→消费写库"的完整闭环——幂等复合语义的候选（唯一键 + upsert）已在 T2 写死，T1 ADR 只需复核；**@Modifying 机检同批落地**（约束新增条），这类雷在批 3 密度最高，不允许继续"待议"。
3. 许可：annona 本身 AGPL-3.0，iText 的 AGPL 许可面兼容（定稿时已核）；**真正的核查点是中文字体**（朱雀仿宋的许可——借鉴地图 P1b-09 行：先核对再决定继用还是换 Noto），已前置到 T-demo 期顺手完成，不压到 T6 开工。
