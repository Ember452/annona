# ADR: planner 决策内核的数据契约与接线方案

- 日期：2026-09-30（初版）/ 2026-10-01（修订 1、修订 2）/ 状态：Accepted
- 相关：[direction ADR 修订 3](./2026-09-25-direction-master-data-adr.md)（遗留义务：扩展 SignalSnapshot 方向维度）·[interview-session-adr](./2026-09-29-interview-session-adr.md)（组卷与冷热分层、历史去重双判）·[evaluation-pipeline-adr](./2026-09-30-evaluation-pipeline-adr.md)（interview_report 四留痕）·设计文档 §6.1/§6.2/§6.4

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

---

## 修订 1（2026-10-01）：修正两个会让决策空转的接线缺陷

**背景**（P1c 收尾后的代码审查发现，两条均在 387 测试全绿的状态下存在）：初版把“信号回看窗口”
同时用在了学习侧与面试侧，且未安排复习选题与历史去重的相互关系。后果：

1. `recentSessions` 按 `window-days`（14 天）取数 → 遗忘衰减的输入 t 被硬截在 14 天以内
   （半衰期 H=21 天，0.5^(14/21)=0.63），而“久不练”的方向窗口内一场都没有 → sampleSize=0
   → SAMPLE_GUARD 拦停。**产品立项场景（“一个月没练的方向被重新抽出来”）在数学上不可表达**；
   `DemoSeedRunner` 造的 4 场面试落在 2/13/23/34 天前，默认参数下只有 2 场进窗 →
   demo 与 A/B 取证只能演示出“数据不足”。
2. 复习题候选来自“历史得分最低的题”（本质就是答过的题），而 `QuestionPackService` 先按
   90 天历史去重剔除候选、再对剩下的做 review 置顶 → FORGETTING 选出的复习题**必然被去重
   拦光**，但 `REMIND_REVIEW` 留痕仍写“已优先复习 N 题”——**可解释链给出一条做不到的承诺**。
   `ab.py` 旧版度量的是 trace 行数（规则声称）而非卷面事实（真的做了），所以 A/B 即
   使跑了也抽不出这个缺陷。

**决策**：

1. **拆两个口径**：`window-days` 只作用于学习侧（自习室时长回看）；面试侧事件集改为
   **按条数取最近 10 场**（= SPI 既有的 `recentSessions` 上限），端口方法由
   `recentOutcomes(userId, directionId, from, to)` 改为 `latestOutcomes(userId, directionId, limit)`，
   SQL 去掉 `finished_at` 谓词。条数上限是契约的一部分，写为 `SignalFacade` 常量，**不加配置键**。
2. **样本量与均分同分母**：`sampleSize` = 有非降级分的场次数（不再是“完成场次数”），
   `avgScore` 与掌握度事件均基于同一集合——面板里“近 N 场均分 X”的 N 必须是 X 的真分母。
3. **复习题豁免历史去重**：`pack()` 对 `reviewIds` 里的候选不应用 dedup（仍受池内同题干
   折叠与难度置顶约束）。interview-session-adr §决策 4 的去重目的是“不出新重复题”，
   重练已知弱项是被授权的例外。
4. **留痕只说真做到了的事**：组卷完成后调 `reconcileReview(decision, packedIds)` 校正
   `REMIND_REVIEW`——部分落地写 `actual/planned`，一支未进卷时 action 改为
   `REVIEW_NONE_PACKED` 并明写未落地。不采“删掉承诺行”的方案：保护/降级也要可解释（§6.4）。

**否决的备选**：

| 备选 | 否决原因 |
|---|---|
| 只把 `window-days` 默认值提到 60/90 | 一行改完，但“近 N 场均分”会被很旧的成绩稀释，均分作为“近期表现”的语义丢失；且仍需在注释/ADR 里维护“窗口必须 ≥ 2×半衰期”这条隐式不变量 |
| 复习题走“跳过 dedup 的旁路查询”另开一条组卷路径 | 两条路径同时改动时极易漂移；在 pack 内加一个豁免条件是一个变更点 |
| advisor 自报“已掺入”，信任 reviewIds 非空 | 正是本次缺陷的根源：选择≠进卷，组卷结果只有组卷方知道 |
| `REMIND_REVIEW` 直接不写 trace（静默降级） | 违反“保护与降级也要留痕”（§6.4），用户会看到“说了要复习却没出现”且无法归因 |

**后果与约束**：

- 契约描述同步点（已随本批改完）：`SignalSnapshot.sampleSize/recentSessions`、
  `DirectionSignal.sessions/avgScore`、`RuleConfig.windowDays` 的 Javadoc，以及
  `application.yaml` 的 `window-days` 注释。改口径不改注释 = 下次再犯。
- `DecisionFlowIT` 新增两条真库断言作为不变量守卫（久不练仍命中 WEAK_DIRECTION；复习题真进卷）——
  本机无 PG 不跑，由 CI docker-it 复验（AGENTS 规则 8）。
- **何时重新评估**：若未来要支持“均分只看最近 N 天”的真实需求，应新增一个显式的
  `score-window-days` 配置并与事件上限分离，而不是把窗口谓词加回 `latestOutcomes`。

---

## 修订 2（2026-10-01）：驳回到达、日界口径与注释真相

**背景**：修订 1 之后再把 P1c 逐文件过了一遍。六处问题**先逐条确认存在再动手**（包括 1 条
确认后发现不是缺陷，故只补注释不改代码）。

| # | 确认到的事实（怎么确认的） | 处置 |
|---|---|---|
| 1 | 驳回与声誉计数都是 `findById → 判 → 改实体 → save`（grep 确认无 `@Version`、无 CAS）：并发驳同一条 trace 会各计一次，阈值 3 被虚胖推爆；两路同时建行还会撞 `uq_reputation_user_rule` 把 500 透给用户 | trace 走条件 UPDATE（`markRejectedIfOpen`，0 行即 3201）；声誉走 `on conflict do nothing` 建行 + 一条 UPDATE 把计数与停用求完（与登录锁定计数、interview fencing 同口径）；新增 `DecisionFlowIT.concurrentRejectsCountOnce` |
| 2 | 日界在一次决策里有三种口径：学习端口 `Asia/Shanghai`、mastery 参考时刻 `ZoneOffset.UTC`、Facade `LocalDate.now()` 跟 JVM 默认区 | 新增 `common/support/AppZones.DAILY` 单一出处，收敛 study×3 + planner + Facade + demo；**不做成配置键**（它是产品口径不是部署差异） |
| 3 | `DecisionTrace.rejectedBy` 注释说“记否决者 ruleKey”，但 grep 证实全仓除 `"USER"` 外无任何生产者（guard 是前置拦截，规则被拦时根本不执行）；`action` 举例的 `CAP_DIRECTION` 也不存在 | SPI 注释改为实际取值集合并标为“语义位”（发 Central 的契约不能写做不到的语义）；ADR 决策 5 的“rejectedBy 改写 replaced trace”按实现形态作废，以本表为准 |
| 4 | `DecisionRule` SPI 注释宣称“已定五条规则（P1c 落地）”，实现只有 2 条 `DecisionRule`，其余四条在 `GuardEngine`，CAP_RATIO 一条都没有 | 注释按职责边界重写（调整型走 SPI，保护型走 guard），不再列不存在的实现 |
| 5 | `cap-ratio: 0.40` 与 `PlannerProperties.capRatio` 零消费方（修订 1 前就已被重定位）| 删键删字段；`application.yaml` 留一句“为什么不设这个键”，避免下个新人当漏配补回来 |
| 6 | V15 头注释写“trace 随会话事务写入”，而 `create()` 无 `@Transactional`、Writer 独立事务（两者必有一个在说谎）| **不回改 V15**：已应用迁移被 Flyway checksum 冻结，改一行注释就会让所有持久库启动失败（AGENTS §4）；以本 ADR 与 `DecisionTraceWriter` 的 Javadoc 为准 |

**确认过后发现“不是缺陷”的一条**：修订 1 报告里怀疑“golden 的期望难度序列等于基线，钉不住‘决策真改变了组卷’”。实跑核对：
`RuleChainTest.weakDirectionRaisesDifficulty` 已经断言了 `3,3,3,3 → 4,4,4,4` 的非中性序列，该职责并不缺位；
golden 钉的是规则顺序与阈值组合。所以**没改 golden**，只在两个测试的 Javadoc 里把“各自钉什么”写清。

**后果与约束**

- “保护与降级也要留痕”多了一个实例：复习题一支未进卷时不得静默删痕，而是写
  `REVIEW_NONE_PACKED`（修订 1 已定），本修订把它的验证补到了真库 IT。
- 日界新增代码只能引用 `AppZones`：再出现第四处 `ZoneId.of(...)` 就按 §4 元规则配机检
  （下一条触发即上 ArchUnit/脚本断言，不靠 review 记忆）。`MeteredModelProvider` 的配额日键与
  上传路径日期仍用系统默认区（属 P1b 语义，本批未动）——若改成 `AppZones.DAILY` 要先确认
  已有配额/对象键不跨日重建。
- `orchestrator → planner/advisor` 这条白名单边从本批起有 ArchUnit 规则 8 守（含一条“边确实存在”
  的反空转断言）；旧文档提到的 `archunit-whitelist.properties` 从未存在，白名单以规则形式住在代码里。

**何时重新评估**：面板要展示“这条判断有多可信”时，再把 §6.2 的 `quality_weight` 与 `confidence`
一起接上（需要定死质量均值的量化口径，属产品决策，不先写没人读的数）。

---

## 修订 3（2026-10-02）：时间戳类型与“一行脏数据停摆整个决策层”

**背景**：修订 1/2 推上去后，docker-it 里四条带 seeded 历史的 IT 全红、三条无历史的绿。本机无 PG，
靠一个无数据库的全链切片测试（`PlannerDecisionChainSliceTest`，修订 2 里补的那类接缝测试）定位到：
原生 `Object[]` 查询上的 `timestamptz` 由 Hibernate 6 返回 `OffsetDateTime`，而行映射只认
`java.sql.Timestamp` → 真实行的 `finishedAt` 全为 null → 掌握度事件排序 NPE → `advise()` 抛出 →
`InterviewSessionFacade` 的 `catch (RuntimeException)` 把它降级成“本次由默认策略出题”——
**决策层不是“没调到难度”，而是一整条链静默停摆，一行类型不匹配就能做到**。
既有 slice 测试都注入 `Timestamp`，所以这个错在本机永远不可能出现——这就是 mock 行为代替真行为的代价。

**决策**：

1. `toInstant` 同时接受 `Timestamp` / `OffsetDateTime` / `Instant`；未知类型不猜值也不抛，返回 null。
2. “有效样本”同时要求 `compositeScore` 与 `finishedAt` 非空（SPI 已写明）：无时刻的行算不出
   衰减天数，喂进事件集只会在下游炸。现在它走 `SAMPLE_GUARD` → 面板显“数据不足”，
   **降级可见而不是静默停摆**（§6.4 保护 1 与诚实呈现的同一条要求）。
3. `lastPracticedAt` **故意不跟着收窄**：降级场也是真练过，“上次练习 N 天前”问的是练没练，
   不是评分成没成功。要同一分母的是 sampleSize 与 avgScore，不是这个字段。
4. `DecisionFlowIT` 的三计数探针（`dbDoneReports / portRows / snapshotSampleSize`）留下：
   它是本次能在一行日志里定位到类型映射的原因，也是以后区分“没插进去 / 没读出来 /
   读出来但不可用”的唯一手段。

**后果与约束**

- 真实类型差异只能在真库上暴露：**凡新增读 `timestamptz` / `jsonb` 的原生 `Object[]` 查询，
  必须配一条 `@Tag("docker")` 真库断言**，不能只靠 mock 行形状（本仓已有四次假绿同源教训）。
  触发条件：下一次出现同类映射 bug 时，把它升级成机检（例如约定一个行映射类型的 ArchUnit/脚本断言）。
- Facade 对 `advise()` 的兜底仍保留（决策失败不得阻断开面是对的），但它把一切异常变成一行
  warn 日志，现场只能从“没有留痕”反推。要改成可区分的降级原因需动 `SessionView.skippedReasons`
  语义，属面板改版范围（P2 触发），本批不预置。
- **何时重新评估**：若以后引入自定义 `@Converter` 或改用投影接口读时间列，本条映射层可废弃。
