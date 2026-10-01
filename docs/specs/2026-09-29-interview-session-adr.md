# ADR: 面试会话数据模型与组卷/状态机决策（P1b 批 2）

- 日期：2026-09-29
- 状态：Accepted
- 相关：[批 2 计划](../plans/P1B_BATCH2_INTERVIEW_CHAIN_PLAN.md)（M3/M6/M7/M8 修订在此定稿）、
  [skill-questionbank-adr](./2026-09-29-skill-questionbank-adr.md)（追问定位先例）、
  [direction-master-data-adr](./2026-09-25-direction-master-data-adr.md)（修订 2：方向一律 `direction_id` 外键）

## 背景

批 2 要打通"组卷→逐题作答→断线续面→交卷"闭环，评分与报告留批 3。上游 🅖 的会话模型是
`requestId` 幂等 + questionsJson 内嵌会话行 + 历史去重**纯字符串精确匹配**；🅜 是 Redis 分布式锁
（`tryLock(wait=0)`）守 finalize。两者都有不适配点：精确匹配对换皮题干零召回；锁方案把幂等
押在 Redis 可用性上，且 annona 的 Redis 是可缺席门控（AGENTS 门控禁令）。组卷的向量判重也碰到
同一个事实：**题目没有向量**——`spi.retrieval.Retriever` 服务的是 `kb_doc_chunk`，拿它做题干
相似判在现架构下不成立。

## 决策

1. **两张表**（V9）：`interview_session`（status `RESUMABLE/COMPLETED/ABANDONED`、`plan JSONB`
   存服务端校验后的组卷定稿、`current_index` 恢复位、`evaluator_version` 批 2 写 `'v1'` 只作幂等
   契约）+ `interview_answer`（建会话时按组卷整排 PENDING 占位，作答=条件 UPDATE；
   `uq(session_id, question_id, follow_up_index)` 是幂等的 DB 级兜底）。追问不是独立题目行
   （🅢 同构：`follow_ups` 在 `qb_question` JSONB 内），所以作答寻址 = 会话内的
   `(question_id, follow_up_index)`（uq 已保证唯一）；`current_index` 只按主问题推进
   （追问展平在评估侧，展示层不暴露层间导航）。
2. **InterviewPlan 契约**：`record InterviewPlan(int totalCount, List<Integer> difficulties, int followUpDepth)`，
   服务端校验上限 `totalCount ∈ [1,20]`、`difficulties ⊆ [1,5]` 且长度==totalCount、`followUpDepth ∈ [0,3]`；
   越界 `1001`，容量不足**复用 2604**（M7：与批 1 容量校验同一失败语义，不开双码）。快照结构版本
   由 Service 校验器持有，不入 plan JSON（运行期从不按 v 分支，加了就是死字段）。
3. **题目向量列**（M3）：`qb_question.embedding vector(1024)`，出题落库后 best-effort 异步嵌入，
   失败留 NULL 不阻塞——NULL 行自动降级为只做关键词判。否决独立 `question_embedding` 表：一题
   一向量没有多版本需求，join 与一致性点纯属自找的复杂度。
4. **去重双判阈值**（可解释口径，代码侧 `PackRules` 同步）：向量 cosine ≥ **0.92** 排除——0.9 以下
   存在"同考点不同问法"的正常题对，误杀召回比漏放重复更伤用户体验；关键词判 = Tokenizer 切词
   取前 **5** 个实词全部 ILIKE 命中才排除——题干 ≤300 字，5 个实词全包含即近似复读，部分包含
   误杀率高（技术关键词跨题复用是常态）；历史窗口 = 该用户近 **90 天**已作答 ∪ 本方向 ACTIVE 池
   （备考周期典型 4–8 周，🅖 只回溯 10 场会话不作窗口化，取其"少扫快判"精神换时间口径）。
5. **状态机与 ABANDONED 入口**（M6）：全部转移 = repository 条件 UPDATE（fencing，沿用
   `QuestionGenStateService` 先例）。ABANDONED 两入口：新建同方向会话时旧 RESUMABLE 自动置
   ABANDONED（服务端返回可读提示）+ 手动 `POST /sessions/{id}/abandon`。不做超时自动废弃——
   恢复扫描走 partial index，堆积量级可控，定时任务是第 4 个移动部件不值。
6. **交卷幂等**（M8）：唯一守门 = `RESUMABLE→COMPLETED` 条件 UPDATE affected-rows；赢者才写
   `evaluator_version` 并批量置 SUBMITTED。否决 🅜 的 Redis 锁前置：锁失效/Redis 缺席时幂等
   跟着失效，DB fencing 单点即足，`uq_answer_slot` 兜底双写。批 2 finalize 只落库不评分。
7. **冷热分层**：Redis 存会话快照（24h TTL，借 🅖 key 结构 `interview:session:{id}`），仅作读加速；
   DB 永远是真值，miss/Redis 缺席一律回落 DB 重建。写路径 DB 先行、缓存尽力失效。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 复用 `spi.retrieval.Retriever` 做题干相似判 | 它的检索对象是 `kb_doc_chunk`，返回 docId/chunkId，题目不在其语料内——语义通道根本走不通，不是调参能救的 |
| 历史去重沿用 🅖 字符串精确匹配 | 对"换皮题干"零召回，用户刷两场就撞题；annona 已有 pgvector 基建，边际成本低 |
| 🅜 式 Redis `tryLock` 守 finalize | 幂等被绑在 Redis 可用性上，与门控降级（Redis 可缺席）矛盾；DB 条件 UPDATE 已经是原子的 |
| 超时定时任务批量置 ABANDONED | 多一个移动部件；partial index 下堆积不伤读路径，等真出现百万级再评估 |
| plan JSONB 内嵌 `v` 版本字段 | 运行期无人按版本分支；结构版本放校验器类常量，改结构 = 新校验分支 + 本 ADR 修订 |

## 后果与约束

- 批 3 评估消费 `evaluator_version`（`'v1'` 起）与 `plan` 快照；评分器升版必须新值而非改语义。
- `interview_answer` 占位在建模时展开追问——改 `followUpDepth` 语义 = 组卷与占位两处同改。
- embedding 列进 `FlywayBaselineIT` 门禁范围之外（加列不建表）；换嵌入模型维度需新迁移重建向量列
  （与 kb_doc_chunk 同一口径，retrieval-hybrid-adr §后果）。
- 2604 的文案要能同时服务"出题后开始面试"与"直接开始面试"两条入口（措辞已兼容）。

## 何时重新评估

- 题目池 >10 万行：HNSW `m/ef_construction` 参数与关键词 ILIKE 的全池扫描需重测。
- 组卷需要跨方向/混合难度策略时：`PackRules` 从常量升为 planner `DecisionRule` SPI 实现。
- 批 3 评分对 plan 结构提出 breaking change 时：修订本 ADR 而非静默改 JSON 形状。

## 后续修订

- **2026-10-01（§决策 4 历史去重的一处例外）**：planner 选定的复习题（`pack(..., reviewIds)`）
  **豁免**历史去重。去重的目的始终是“不出新重复题”，而重练已知弱项是决策层的合法输出，
  不应当被去重静默吃掉（否则 `REMIND_REVIEW` 留痕与卷面不符）。动机、否决备选与守卫测试
  见 [planner-decision-kernel-adr 修订 1](./2026-09-30-planner-decision-kernel-adr.md)，本文件不复述。
