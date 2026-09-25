# annona 全局架构概览

> 这篇解决什么问题：不看代码就能回答"一个请求进来会经过哪些层、谁能依赖谁"。
> 详细包清单见 [../annona-项目结构.md](../annona-项目结构.md)，功能与领域模型见 [../annona-项目设计文档.md](../annona-项目设计文档.md)。

## 1. 分层与依赖方向

```text
┌───────────────────────────────────────────────────────────────┐
│  annona-web  React 19 + Vite + TS + Tailwind4 + three.js      │
└───────────────┬───────────────────────┬───────────────────────┘
            REST /api/*            SSE /api/stream/*  ·  WS /ws/voice
┌───────────────┴───────────────────────┴───────────────────────┐
│  annona-server   io.annona                                     │
│  ├─ config/        全局装配（web/async/persistence/security/…）│
│  ├─ bootstrap/     启动校验（缺 KEK 即拒绝启动）+ seed         │
│  ├─ cli/           运维命令面（export/reindex/eval/seed）      │
│  ├─ shared/        跨模块契约：direction / signal / 领域事件   │
│  └─ modules/       16 个业务模块（controller-service-repo）    │
└───────┬───────────────────────────────────────┬───────────────┘
        │ 编译期只依赖接口                       │ runtime 装配
┌───────┴─────────┐                    ┌────────┴──────────────┐
│   annona-spi    │◀──── implements ───│  annona-infrastructure │
│  5 个扩展点契约  │                    │  PG/pgvector·Redis·S3 │
└───────┬─────────┘                    │  LLM HTTP·加密·PDF·分词│
        │                              └───────────────────────┘
┌───────┴─────────┐
│  annona-common  │  Result<T>·异常·枚举·工具·注解（零业务）
└─────────────────┘
```

**唯一允许的依赖方向**：`server → spi → common`，`infrastructure → spi → common`，`server` 在运行期装配 `infrastructure` 实现。业务代码 `import` 到 `infrastructure` 具体类即 ArchUnit 失败。

## 2. 五个扩展点

| SPI | 作用 | 内置实现 | 切换方式 |
|---|---|---|---|
| `IdentityProvider` | 认证与会话 | 本地账号 / 平台账号 / 单机免登录 | `annona.identity.mode` |
| `ModelProvider` | 模型调用与 Key 归属 | BYOK / 平台代持 | `annona.model.mode` |
| `Retriever` | 检索后端 | PgVector（默认）/ ElasticSearch（可选） | `annona.retrieval.backend` |
| `LearningSignalReader` | 学习信号读取 | 采集库实现 / 空实现（未启用自习室时） | 模块存在性 |
| `DecisionRule` | 决策层规则链 | 遗忘曲线 / 薄弱方向 / 保护规则… | 每条规则独立开关 |

## 3. 内核模块关系（其余模块都是常规 MVC）

```text
 study ─┐                            knowledge ─┐
 plan  ─┼→ shared.signal ─→ planner ─┼→ interview ─→ evaluation
 checkin┘        ↑                   │        │            │
        shared.direction ────────────┴────────┘            │
        questionbank ──(题库/评分标准)──→ interview         │
                ↑                                          ↓
             retrieval ←── 知识库文档            decision_trace（留痕）
                                                     ↓
                                             explain-panel / report
```

三条硬规定：

1. **`shared/direction` 是全局主数据**：`study` 的科目、`questionbank` 的方向、`planner` 的掌握度、`interview` 的考察方向，全部外键到 `direction`，禁止用自由文本对接。
2. **`planner` 不依赖任何业务模块**，只读 `shared.signal` 与 `shared.direction`，通过 `advisor` 被同步调用；这是它可测试、可关闭的前提。
3. **`evaluation` 独立于 `interview`**：文字面试与语音面试共用同一评估引擎，若放在 `interview` 内会造成 `voice → interview` 的反向依赖。

## 4. 一次面试请求的生命周期

```text
POST /api/interview/start
 └─ InterviewController            校验 + 委托（无业务逻辑）
    └─ InterviewOrchestrator
       ├─ PlannerAdvisor.advise()  读信号 → 规则链 → 保护规则 → InterviewPlan
       │                            └─ 落 decision_trace（可解释面板数据源）
       ├─ QuestionBankSelector     按方向配额+难度取题（去重）
       │  └─ 不足 → KnowledgeQuestionGenerator（实时出题，走 ModelProvider）
       └─ SessionStateMachine      INIT → IN_PROGRESS，快照写 Redis + DB

POST /api/interview/{id}/submit
 └─ EvaluationService              幂等键(session_id + evaluator_version)
    ├─ 分批 → StructuredOutputInvoker（统一重试）→ 二次汇总
    ├─ ComparabilityResolver       难度加权、版本留痕
    ├─ @Transactional 落 evaluation_result + 更新 mastery
    └─ 发 InterviewEvaluated 事件 → notify / planner 反哺任务
```

**事务边界铁律**：LLM、S3、外部 HTTP 调用一律在事务外；事务只包裹本地数据库写。上面的 `KnowledgeQuestionGenerator` 与 `EvaluationService` 分批调用都发生在开启事务之前。

## 5. 异步与流式

| 通道 | 用途 | 实现 |
|---|---|---|
| Redis Stream | 文档向量化、简历分析、出题、报告生成 | `AbstractStreamProducer/Consumer` 模板，消费前校验实体存在 |
| SSE | RAG 流式问答、ETL 进度、评估进度 | 不缓冲（Nginx 侧配 `X-Accel-Buffering: no`） |
| WebSocket | 语音面试双向音频 | 上行 PCM / 下行音频块 + 字幕事件 |
| 线程池 | 通用 / AI-IO / CPU 密集 / 查询 | 显式 `ThreadPoolExecutor`，禁用 `Executors.newXxx` |
