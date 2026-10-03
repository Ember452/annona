# ADR: plan 模块——计划、任务与打卡联动（P2-06）

- 日期 / 状态：2026-10-03 / Accepted（含修订 1，见文末）
- 关联：[study-collection-adr](./2026-09-26-study-collection-adr.md)（时长单一真相源）·[llmprovider-metering-adr](./2026-09-29-llmprovider-metering-adr.md)（记账）·[开发计划 §P2-06](../annona-开发计划.md)

## 背景

P2-06 要求「计划—任务—打卡联动、今日待办、文档工作室（MD→AI 拆任务）、模板库」。上游 🅢
有完整的 plans/studio 实现，但它的打卡与计划进度互不相联（`checkin` 只是到访计数，
plan 进度 = 已勾选任务/总数）；「打卡回写进度」是 annona 自己的需求，语义必须新定。
用户裁决（2026-10-03）：**方向联动自动累计**——任务绑定方向后，该方向的有效专注/打卡
时长按「最旧未完成优先」瀑布式累计进任务进度；工作室做完整三面板移植（超 P2 预算
2.5~3 人日，用户知情接受，进度表如实记录）。

## 决策

1. **两张新表（V17）**：`plan`（user_id、direction_id?、title、document MD 全文、
   source_hash）与 `plan_task`（plan_id、user_id 冗余、direction_id?、title、description、
   category、priority、status PENDING|DONE、target_minutes NOT NULL DEFAULT 25、
   progress_minutes、source AI|MANUAL）。任务归属计划（级联删除）；`user_id` 在任务上
   冗余一份，让「今日待办」跨计划一条查询拿全，不必 join plan。
2. **MD→任务拆分**：`POST /api/plans/{id}/split`，走 `StructuredOutputInvoker` +
   `UsageContext.bind(userId, "PLAN", planId, null)`（计量/配额由 MeteredModelProvider
   自动接住，`token_usage.scene` CHECK 在 V17 扩 'PLAN'）。输出形状与上游对齐：title≤160 /
   description≤600 / category ∈ study|project|review|exercise / priority ∈ high|normal|low /
   targetMinutes 5..600（非法值归一化，不重试）/ ≤30 条。**标题归一化 reconcile**：
   匹配项只更新内容字段（status/progress 保留）、新增插入、缺失且 PENDING 才删
   （DONE 的不删——历史不因重拆而丢）。`source_hash` 相同则短路返回，不重复花 token。
   **同步执行**（不搞上游的 splitting 状态机 + 2s 轮询）：上游异步是 Next Serverless
   的产物，annona 后端长驻、StructuredOutputInvoker 自带重试，一次请求返回即可。
   事务边界按**修订 1** 执行：模型调用必须在事务外，写入走 TransactionTemplate 短事务。
3. **打卡联动（用户裁决语义）**：`CheckinService` 在打卡**首次创建且 hours>0** 的事务内
   发布 `CheckinLinkedEvent(userId, directionId, minutes, day, checkinId)`；plan 侧监听器
   AFTER_COMMIT + REQUIRES_NEW，把 minutes 瀑布式累计进该用户该方向 `direction_id` 匹配的
   PENDING 任务（`created_at` 升序）：`add = min(remaining, target - progress)`，进度达
   target 自动 DONE。**只在首建时发**：打卡当日多次修改 hours 不重复回写（幂等靠
   "只发一次"而非对账表——重算语义见"否决的备选"）。target_minutes 为 25 缺省，AI 拆分
   可给估时；手动建任务落 25。
4. **工作室三面板**：`/plan/{id}` 编辑器（textarea）+ 预览（react-markdown）+ 大纲侧栏
   （regex 提取 + 拖拽重排换行序——重排只动 markdown 标题行顺序）+ AI 面板
   （`POST /api/plans/{id}/studio/chat` SSE 三事件 token/done/error，上下文=计划文档，
   同样走 PLAN scene 计量）。保存 debounce 1200ms + Ctrl/Cmd+S；导出 .md 走 Blob。
   AI 对话不落库（会话历史在前端内存，刷新即失——v1 明确不承诺对话记录）。
5. **门控**：`annona.plan.enabled`（缺省 true）管 controller + split + chat + 联动监听器；
   关闭 = 整个模块消失，打卡照常（事件无人消费，Spring 事件无订阅者即丢弃）。
6. **错误码**：plan 占 3300–3399 段（sequential）。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 独立 todo 表（上游 use-todos 形态：全局待办与计划无关联） | 打卡联动必须按方向归属到计划任务；游离 todo 无法解释"进度回写到了哪个计划"。annona 的任务必须挂在 plan 下 |
| 纯手动勾选联动（用户裁决的另一个选项） | "回写"只是手动记录，与 annona「系统自动做 + 可解释」取向相悖；方向联动让打卡页和计划页自动保持一致 |
| 打卡每次 upsert 都发事件 + 对账（按 checkin 记录已回写分钟数，重算差额） | 能处理"改 hours"场景，但引入第三张表和一个差额重算协议，复杂度远超收益。v1 取"首建发一次"，改 hours 不补差（ADR"何时重新评估"列了触发条件） |
| 拆任务异步化（splitting 状态机 + 前端轮询，照搬上游） | 上游异步是 Serverless 超时约束的产物；同步请求省一个状态机、一张状态字段和轮询 UI，长驻后端没有超时问题 |
| 任务进度用百分比列 | 派生值（progress/target）落库必漂移；DTO 里现算 |
| 工作室只做精简版 | 用户裁决要完整三面板（AI 对话面板 / 拖拽重排 / 查找高亮），预算超支知情接受 |

## 后果与约束

- `plan_task.progress_minutes` 只由联动监听器与 reconcile 增量维护；任何"手动改进度"
  的入口都不许加（要加先回这个 ADR）。
- 联动只消费 checkin 的事件；**study_session 的正常 finish 不发事件给 plan**（专注分钟
  已经通过打卡的自报口径进入，双发会重复计数）。
- `token_usage.scene='PLAN'` 的账单口径与 QA 一致（同一日配额池）。
- 新包 `io.annona.modules.plan` 有 package-info；跨模块只读 direction 走 QueryService。
- V17 冻结；表清单登记进 `FlywayBaselineIT`（29 表）。

## 何时重新评估

- 用户反馈"改打卡时长必须补差"成为普遍诉求 → 引入对账（按 checkinId 记录已回写分钟，
  差额重算）；
- AI 对话记录被要求持久化 → 给 chat 加历史表（qa_message 形态可复用）；
- 任务需要到期日/重复规则 → 这已是 todo 应用范畴，重估是否引入独立日历模型。

## 修订 1（2026-10-03，P2 收尾批实测）

首版代码落地后经 P2 收尾批审查修正三处，决策语义不变、实现约束落定：

1. **拆分的事务边界**：首版 `split()` 带 `@Transactional`，模型调用实际在事务内（与 AGENTS
   §0.3 冲突，当时注释却声称不在——注释/代码漂移的典型）。现改三段式：只读取数（autocommit）
   → 事务外调模型 → `TransactionTemplate` 短事务写 reconcile；并新增反射守卫
   `TransactionBoundaryTest`（split() 再挂 @Transactional 即测试红），把这条约定升为机检。
2. **联动事件的竞态防线**：`CheckinService` 首建判定在 READ COMMITTED 下双事务可互不可见、
   双发 `CheckinLinkedEvent`（唯一约束只防重复行不防事件）。现用事务级 advisory lock
   （`pg_advisory_xact_lock`，键 = user×day 哈希）把同 user×day 的 upsert 串行化；否决
   upsert-RETURNING（绕过 JPA 生命周期，拆掉 saveAndFlush+refresh 受管语义）。真库双发
   回归验证归 CI 的 PlanFlowIT。
3. **列表进度改批量**：`PlanService.list` 首版逐计划 2N 次 count（违反禁循环调 DB），
   改单条 GROUP BY 聚合（`countSummaryByUser`）。
