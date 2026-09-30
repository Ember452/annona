# ADR: P1b-10 Provider 宽口径配置与模型计量（扩展 KEK ADR，不推翻）

- 日期：2026-09-29
- 状态：Accepted
- 相关：[model-api-key-adr](./2026-09-25-model-api-key-adr.md)（本 ADR 是其**扩展**：五用途→六用途、
  表名落 `llm_provider_config`；三列密文结构与 fail-fast 裁决原样继承，未重开）、
  [interview-session-adr](./2026-09-29-interview-session-adr.md)（token_usage 消费其幂等键）、
  [批 2 计划](../plans/P1B_BATCH2_INTERVIEW_CHAIN_PLAN.md)（M4/M5 在此定稿）

## 背景

原计划宽口径一次性建 chat/embedding/rerank/tts/asr 五类 Key——其中三类在 P3 语音前用不上，
属计划外加码；同时批 3 评估器需要 EVALUATOR 用途 Key 异源（KEK ADR 否决表点名"不区分用途→
将来必须改表"）。计量侧现实比计划假设复杂：仓库有三条模型出口——`ModelProvider.chat`（同步，
出题经 `StructuredOutputInvoker` 走它）、`StreamingChatProvider`（qa 流式）、`EmbeddingProvider`
（向量化），**后两者当前契约都不返回 usage**；`UsageInfo` 只在同步 chat 契约里。配额需求是
"每日 token 上限 + 超额熔断"，而仓库没有 `@RateLimit` 骨架（批 2 计划 M4 已证）。

## 决策

1. **V10 落六用途**：`chat/embedding/rerank/tts/asr/evaluator`——接受 rerank/tts/asr 的提前建设
   （各一列 CHECK 值，边际成本≈0）；**evaluator 必须在 V10 进**，否则批 3 评分 Key 异源要开 V12
   改 CHECK，正是 KEK ADR 预言的事故形态。宽口径 = 表结构宽，**消费窄**：本批只建 CRUD + 连通性
   测试，业务调用仍走 env 全局配置；用户级 Key 路由业务调用留到托管模式议题（依赖 KEK 轮换
   `annona reencrypt` 落地——没有轮换就没有多 KEK 代持的安全前提）。
2. **密文三列结构原样继承 KEK ADR**（nonce + cipher + kek_version），`kek_version` 本批恒 `v1`
   （类初值唯一出处，见 KekProperties 注释）。单列密文方案维持否决。
3. **计量挂点 = `MeteredModelProvider` 装饰器**（`@Primary`，实现 `ModelProvider`）：
   前置熔断 → 委托调用 → 记账，出题链（经 invoker）与批 3 评估器天然覆盖，调用点零改动。
   场景归属经 `common.usage.UsageContext`（ThreadLocal，执行线程内 bind、try-with-resources
   清理）——不把 userId 塞进 `ModelOptions`（spi 是对外契约，不为内部记账扩字段）。
4. **本批不接的计量出口（如实声明，已被批 3 修订接通）**：qa 流式与 embed 向量化**暂不计量**——
   两者 SPI 契约不返回
   usage，接通 = 扩对外契约（触发 spi 变更 ADR）或字符估算（假数据，违反"结论需证据"）。
   成本面板口径必须诚实：只含同步 chat 链。接通时机 = qa-streaming SPI 增 usage 透传时随
   契约 ADR 决定。
5. **每日配额 = `DailyQuotaCounter` 单点组件**（common 端口 + infra Redisson 实现，
   `quota:daily:{user}:{date}` INCR+TTL 至当日 24 时）：**fail-open**——Redis 故障放行 + warn。
   取舍：闸门失效只损失当日成本控制，让面试/出题停摆才是事故；托管代持（真金白银）上线前
   必须重议为 fail-close + 精确 Lua（见 §何时重新评估）。`@RateLimit` 注解骨架不移植（瞬时限流
   与每日配额是两个概念，前者归 P2-05 匿名接口议题）。
   5a. **熔断是近似的（收口批补记，2026-09-30 外审发现登记不足）**：check 与 consume 非
   原子，并发在途调用可小幅超出日限（击穿量级 = 在途请求 token）。选近似：预扣-回滚需要
   调前估算 token，与决策 6“不估算”同源；本阶段单用户自部署下误差无感。托管代持上线前
   与 fail-close、精确 Lua 一并重议（见 §何时重新评估）。
6. **记账不进事务、丢帧可接受**：`UsageRecorder` 在事务活跃时 afterCommit、否则直投
   aiIoExecutor 异步 INSERT；落库失败 warn 丢一帧。账用于展示与配额，不是计费对账单。
7. **连通性探测不记账、不计数**（`LlmConnectivityProbe` common 端口 + infra JDK HttpClient 实现）：
   探测失败文案是产品反馈不是业务错误，且探测请求不应消耗用户配额。
8. **executor 偏离**（相对计划）：加密（微秒级纯计算）、聚合查询（毫秒级单条 GROUP BY）均同步——
   为单条操作抛池再 join 是为异步而异步；池留给批量场景（reencrypt 重加密、未来的账行归档）。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 调用点各自埋点记账 | 重复代码必然漂移；新场景"忘了记"无守卫——挂点单点是装饰器的全部意义 |
| `ModelOptions` 扩 userId 字段 | spi 契约 jar 为内部记账开门子；options 是"调用参数"不是"计费归属"，语义错位 |
| 字符估算填 qa/embedding 的 token 账 | 假账比缺账毒：成本面板数字不可读时整列不可信（"结论需证据"） |
| 配额 fail-close（Redis 挂 → 拒绝所有模型调用） | 把成本控制闸门升级为全站单点；自部署用户被无 Redis 环境锁死在门外 |
| 本批就接用户级 Key 路由业务调用 | 依赖 KEK 轮换（未落地）与消费链重构（网关语义变更），塞进面试链批次两头都做不好 |
| V10 只建五用途、evaluator 批 3 再加 | 加 CHECK 值 = 新迁移 V12 + 重放冻结风险；本批一列 CHECK 的边际成本≈0 |

## 后果与约束

- `token_usage.scene` 枚举与 V11 CHECK 双处一致；扩场景 = 迁移 + `LlmProviderService` 同款"改两边"纪律。
- ~~成本面板上线时文案必须带口径（"仅面试/出题链；问答与向量化未计量"），等第 4 条接通再改。~~
  批 3 修订后面板口径改为"含问答与向量化"（面板文案随批 4 落地时按此写）。
- `GET /api/usage/session/{id}` 聚合钉 user_id——成本数据属用户隐私。
- 探测实现日志只记状态码与异常类型；任何把明文 Key 写进日志/异常/缓存的实现违反 KEK ADR §决策 5，
  评审按 `LlmProviderService` 的 decrypt 调用点清单核（当前仅 `testConnection` 一处）。

## 何时重新评估

- 托管代持上线前：fail-open→fail-close、Key 轮转、用户级路由三者一起议（KEK ADR 后果 4 的
  `annona reencrypt` 是硬前置）。
- ~~qa-streaming 契约加 usage 透传时（随契约变更 ADR 把流式链接入计量）。~~
  **已发生（2026-09-30 批 3 修订）**，按本条约定接开——见下节。
- 单日账行 >10 万：账表按月分区或转聚合表，`idx_usage_*` 重测。

## 修订（批 3，2026-09-30）：决策 4 的计量债接通

本批契约扩到位，决策 4 的"暂不计量"不再成立；逐条：

1. **契约变更（直接断，不加 default 桥）**：spi `EmbeddingProvider.embed` 返
   `EmbeddingResult(vectors, usage)`；common `ChatStreamListener.onComplete(fullText, usage)`。
   实现方全在仓内（fake + OpenAiCompatible 两处），桥无受益者；spi 处 0.x 线，semver 允许。
   流式 usage 经 OpenAI 兼容的 `stream_options.include_usage` 终帧采集，供应商不回填时记 0
   ——**不估算**（否决表原样有效）。common 新增对 spi 的依赖（usage record 复用）：spi 自身
   零框架，common 的"零框架依赖"不变量不破；重新评估条件 = spi 将来引入任何依赖时重议。
2. **挂点例外（对决策 3 的偏离）**：装饰器只认同步 `ModelProvider`；qa 流式在 **AI-IO 执行线程
   的终态回调**里 bind `UsageContext` + 调 `UsageLedger`（common 端口，实现在 usage 模块）显式记账
   （异步线程拿不到请求 ThreadLocal，bind 必须在执行线程）。否决"把装饰器扩到两个新端口"：
   业务模块不应 import `modules/usage` 的写服务（跨模块写，违反依赖方向），也不该把
   记账实现搬进 infra（infra 够不到 token_usage 表）；折中 = 记账能力以 `common.usage.UsageLedger`
   端口暴露（同 DailyQuotaCounter/TaskStreamPort 先例），各模块经端口注入。
   挂点单点原则维持：**此后每新增一类模型出口，必须回本 ADR 登记挂点**，不许就地写例外。
3. **embed 计量范围**：入库向量化（`KnowledgeVectorizeService`，整档累计一行）与题库
   回填（`QuestionGenerationService.embedBestEffort`）经 `UsageLedger` 端口记账（scene 分别
   `KB_INGEST`/`QUESTION_GEN`，V12 扩 CHECK）；**检索查询向量不记账**——无稳定会话宿主，
   归属不成立，如实声明而非遗漏。
4. **provider 列语义修正（TD-03）**：`token_usage.provider` = 供应通道（配置枚举值，端口
   `channel()`），`model` = 响应模型 id；旧装饰器两处同值使通道归因失真。两列自此语义独立。
5. **SSE 超时同源（TD-04）**：`QaService` 的 emitter 超时 = `2 × streamTimeoutMillis()`（端口
   方法，与 `annona.model.chat.timeout-seconds` 同源），旧 120s 硬编码删除，provider 不报时
   回退 120s。
6. **TD-02**：`KnowledgeVectorizeService` 逐 chunk 事务并进每 `PROGRESS_BATCH_SIZE` 批一个短事务
   （embed 仍在事务外，铁律不动）。
7. **编号占用**：本批 Flyway 的 V12 = scene CHECK 扩展；**评估表迁移从 V13 起编号**（批 3
   计划文本中的"V12 interview_evaluation"顺延）。
