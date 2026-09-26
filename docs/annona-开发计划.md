# 年轮 · annona 开发计划

> 这篇解决什么问题：把设计文档 §14 的 P0–P5 里程碑拆成**可勾选、可验证、有出口条件**的任务清单，并规定每个阶段收尾必须留下什么。
> 关联：[annona-项目设计文档.md](./annona-项目设计文档.md)（做什么）·[annona-项目结构.md](./annona-项目结构.md)（放哪）·[../AGENTS.md](../AGENTS.md)（怎么做、提交与文档义务）

| 项 | 内容 |
|---|---|
| 工作量单位 | 1 人日 = 6 小时有效编码时间，含写测试，不含等 CI 与胡思乱想 |
| 人力假设 | 1 人主导 + AI 协作实现；估时已含 AI 产出的审阅与返工 |
| **验证环境** | **本机无 Docker**：开发期只跑代码正确性校验（`mvn verify` + 前端构建），容器与集成测试全部由 CI 执行（见 `specs/2026-09-25-dockerless-local-dev-adr.md`） |
| 任务编号 | `P<阶段><子阶段>-<序号>`，例 `P1a-07`；任务清单与进度**以本文任务表为唯一真相源**。对外另维**一个阶段一个 issue**（阶段开工前建，正文引用本阶段任务表），commit 正文写 `Task: P0-01`；`Refs: #<n>` 只用于关联 bug / 提案类 issue |
| 总规模 | 约 **88.5 人日**（P0 10.5 · P1 36 · P2 12 · P3 12 · P4 10 · P5 8） |
| 硬规则 | **上一阶段出口条件未全部满足 + 阶段总结未写，不得开始下一阶段**（AGENTS.md §7） |

## 当前进度（唯一状态源）

| 阶段 | 状态 | 出口凭证 |
|---|---|---|
| 文档（设计/结构/计划/ADR）+ 入口（README/LICENSE/.env.example/SECURITY/CONTRIBUTING/CoC/.editorconfig） | ✅ done | 本仓 20+ 个文档文件；LICENSE 为 AGPL-3.0 FSF 原文（已校验） |
| P0 骨架与门禁 | 🔶 **评审后重开（doing）** —— B1–B5 已合并，但 2026-09-26 全量评审发现出口条件②从未被真实验证（集测 job 0 测试也报绿）、traceId 与 `MetaControllerTest` 的声称不成立、V1 的 direction 全局主键与若干约束错误。已修，待 CI 实跑证据 | `mvn -B -q verify` 本机绿 + **`docker-it` 日志里 `Tests run` 非零** + compose-smoke 真实探活日志（回填阶段总结 §3 出口②） |
| P1a 数据与知识底座 | ⬜ todo（前置：P0 出口②证据回填） | — |
| P1b 面试与评估 | ⬜ todo | — |
| P1c 训练决策层 | ⬜ todo | — |
| P2 / P3 / P4 / P5 | ⬜ todo | — |

状态取值：`todo` / `doing` / `blocked(原因)` / `done(日期+凭证链接)`。**阶段级状态看本表，任务级状态看本文各阶段任务表**（不建任务级 issue；阶段 issue 只作对外摘要）。

## 里程碑总览

```text
P0 骨架        P1 闭环内核                    P2 体验留存   P3 语音    P4 编排     P5 打磨发布
───────────  ────────────────────────────  ───────────  ────────  ─────────  ──────────
10.5d        36d = 12 + 14 + 10             12d          12d       10d        8d
             P1a 数据与知识底座
             P1b 面试与评估
             P1c 训练决策层
   ↑              ↑                             ↑                        ↑
 能跑起来      能说出"凭什么考你"            想每天打开          能开口说      自己转起来
```

**为什么 P1 拆三个子阶段**：P1 是全项目最难也最有价值的一段，一次性做完的风险是"憋两个月拿不出可演示的东西"。子阶段各自有出口物：P1a 能问答、P1b 能面试、P1c 能解释，每 2 周左右有一个能截图的里程碑。

**为什么采集放 P1a 而不是 P2**：学习数据的价值随时间累积，上线晚一天就少一天真实样本。P2 做的是采集数据的**可视化**（热力图、3D 岛），那是装饰，可以等。

---

## 借鉴地图（每个板块开工前必须扫描）

### 使用方式（强制）

1. 任务开工前，先扫描该任务在下表中的路径（批量读文件或派 Explore subagent），产出一段**借鉴说明**写在该任务的 commit 正文（或关联的 bug/提案 issue）：读过哪些文件 → 借鉴哪个机制 → 必须改掉的不适配点及理由 → 上游根本没有的部分。
2. **未扫描 = 任务未开始**；阶段 issue 与阶段总结的第 8 节汇总本阶段实际借鉴了什么。
3. 借鉴 = **参考机制、结构、参数取值与测试用例清单**，不是复制代码（见 `specs/2026-09-25-zero-migration-adr.md`）。若确实搬了代码：文件头保留 AGPL 来源注释并在 issue 记录；跨文件成片搬运才需先征得用户同意。
4. 路径均为 2026-09-25 实测存在，**相对各自仓库根**。上游重构后先改本表再开工。

上游仓库根路径：

| 代号 | 路径 |
|---|---|
| `🅖` | `D:\DEVELOP\interview-guide-master`（后端 `app\src\main\java\interview\guide\`，前端 `frontend\src\`） |
| `🅜` | `D:\DEVELOP\java\MockPilot-project`（后端 `MockPilot-mian\AI-Meeting-main\admin\src\main\java\com\hewei\hzyjy\xunzhi\`，前端 `MockPilot-Frontend-main\AI-Meeting-Frontend-main\src\`） |
| `🅢` | `D:\DEVELOP\summer-checkin-master`（`src\`、`server\`、`prisma\`、`scripts\`、`tests\`） |

### A. 后端·骨架与防护（对应 P0 / P1b-10）

| 任务 | 扫描路径 | 借鉴什么 | 必须改什么 |
|---|---|---|---|
| P0-03 统一响应与异常 | `🅖 common/result/Result.java`、`common/exception/{BusinessException,ErrorCode,GlobalExceptionHandler,RateLimitExceededException}.java`、`common/log/ErrorLogSanitizer.java` | Result 结构、ErrorCode 分段、全局兜底不泄露栈、错误日志脱敏 | 包名换 `io.annona.common`；Result 加 `traceId`；ErrorCode 按 annona 模块重排段号 |
| P0-05 配置与限流 | `🅖 common/annotation/RateLimit.java`、`common/aspect/RateLimitAspect.java`、`app/src/main/resources/scripts/rate_limit_single.lua`、`common/config/{CorsConfig,JacksonConfig,S3Config,WebMvcAsyncConfiguration,OpenApiConfig}.java` | 可重复注解 + key 解析器 + Lua 令牌桶的完整组合；异步 MVC 配置 | 限流维度加 token 配额联动（不只 QPS）；CORS 配置需覆盖 SSE 与 WS |
| P0-05 异步与恢复 | `🅖 common/async/{AbstractStreamProducer,AbstractStreamConsumer}.java`、`common/async/recovery/*RecoveryProperties.java`、`common/constant/AsyncTaskStreamConstants.java`、`common/transaction/TransactionalExecutor.java` | Stream 消费模板 + 死信/恢复调度 + 事务边界工具化 | 消费前校验改为「实体不存在则 ACK 丢弃 + 记录 direction 已删」 |
| P0-01/P1 线程池与防护 | `🅜 common/config/thread/{ThreadPoolConfig,ApplicationThreadPoolProperties}.java`、`toolkit/Threads.java`、`common/ratelimit/{RedissonRequestRateLimitService,RequestRateLimitKeyResolver,RequestRateLimitPolicy,RequestRateLimitService}.java` | 多级池隔离参数、队列与拒绝策略选型、Redisson 限流策略抽象 | 池名与 Micrometer 指标绑定；禁止直接搬 `Threads` 工具类（改为显式 Bean）。**禁用 `Executors.newXxx` 靠 ArchUnit 而不是 enforcer**（enforcer 只能管依赖坐标，管不了方法调用） |
| P0-05 依赖名修正（已实测） | `🅖 app/build.gradle` | **Boot 4 的 starter 已改名**：`spring-boot-starter-webmvc`（不是 `-web`）、`spring-boot-starter-flyway`，以及 `-validation` / `-websocket` / `-data-jpa` / `-actuator` | 写错名字会在 CI 上拉不到依赖；`flyway-database-postgresql` 与 `postgresql` 是 `runtimeOnly` 定位 |
| P0-06 Flyway V1 基线 | `🅖 docker/postgres/init.sql`, `🅢 prisma/schema.prisma::{User,Account,Session}`, `🅜 docker-compose.yml` | 表清单形状（session / token / attempt 三件套字段）；PG init 阶段的 CREATE EXTENSION 权限模型 | `init.sql` 只放扩展不放表（表归 Flyway V1，两条权限线分离）；CITEXT email；TIMESTAMPTZ；partial index；`direction` 上游根本没有 |
| P0-07 ArchUnit 七条 | **三仓都没有 ArchUnit** | 无 | 全部自写；§10 规则来自 [结构 §10](./annona-项目结构.md)；`allowEmptyShould` 兜住无匹配类的假通过；`resources/archunit-whitelist.properties` 红名单机制 |
| P0-08 StartupValidator | `🅖 common/ai/ApiKeyEncryptionService.java` 里 `DEV_FALLBACK_KEY` 是**反例**（[model-api-key-adr §背景](./specs/2026-09-25-model-api-key-adr.md) 明写拒绝） | 无（只借"缺 Key 应该报错"的直觉） | 上游静默 fallback，annona 硬拒；`ApplicationEnvironmentPreparedEvent` 时机 + `spring.factories` 注册 `ApplicationListener`；与 `FlywayMigrationStrategy` 双组件分工 |
| P0-09 annona-web | `🅖 frontend/{package.json, vite.config.ts, tsconfig*.json, src/api/request.ts, src/App.tsx}` | Vite + React + TS 骨架与 axios 拦截器 | SUCCESS_CODE 从 200 改到 0；不引入 framer-motion/recharts/dayjs/onnxruntime；`build.outDir` 指向 annona-server static；单 tsconfig（🅖 有 base + app 两层）；pnpm 11 `onlyBuiltDependencies` 白名单 |
| P0-10 Docker 交付 | `🅖 {app,frontend}/Dockerfile`, `🅢 Dockerfile`, `🅢 docker-compose.yml`, `🅖 docker-compose.yml`, `🅜 MockPilot-mian/docker-compose.yml` | 多阶段 build + healthcheck + depends_on 结构；MinIO 与 pgvector 镜像 pin 策略 | 三阶段（web-build 前置到同 Dockerfile）；服务命名 role 化（db/cache/storage/server/web）；MinIO 走 `MINIO_DEFAULT_BUCKETS` env 不建 init container；nginx 不 mount static（走 server 内嵌 static）；pgvector 镜像用 `pgvector/pgvector:pg16` 不用 `postgres:16` |
| P0-11 githooks | `🅖 .githooks/{commit-msg, README.md}` | Conventional Commits 正则骨架与 perl `\p{Han}` 检测工具 | **反向**：annona 要求"标题不含汉字"（AGENTS.md §5）；不强制 `- ` bullet body；pre-commit gitleaks 硬拒无跳过（🅖 无 pre-commit）；Windows 用户走 Git Bash；`make setup` 与直接 `git config core.hooksPath .githooks` 两条激活路径 |
| P0-12 CI 矩阵 | `🅖 .github/workflows/ci.yml`, `🅢 .github/workflows/ci.yml` | concurrency + cancel-in-progress + permissions least-privilege + 单一 backend job 骨架 | 5 blocking jobs（🅖/🅢 都只有 1 个 job）；docker-it 用 `services:` 起 pgvector+redis 而非 compose（runner 托管比 compose 快 30-60s）；compose-smoke 独立 job 是 P0 出口 ② 的机器化；gitleaks 独立 blocking job；`-DexcludedGroups=` 显式清空 pom 默认排除；`mvnw` 用 `git update-index --chmod=+x` 补 exec bit |
| P0-13 Makefile | 三仓都无 Makefile | 无 | 全新引入 8 个 target（`setup / up / dev / test / eval / logs / reset / quickstart`）；与 [AGENTS.md §8.2](../AGENTS.md) "make 目标保留但不作为本机默认入口" 一致；Windows 用户 README 明写走原生 PowerShell 命令 |
| P0-14/15 门面与仓库设置 | 无（🅖/🅢 有 CODEOWNERS 但内容完全不同） | 无 | CODEOWNERS / ISSUE/PR 模板 / FUNDING 全部自写（dependabot 已移出 P0，见 D16）；`ISSUE_TEMPLATE/feature_request.yml` 内嵌 AGENTS.md §1 九条 Non-goals 自查；`skill-proposal.yml` 对齐 §13.3 表格新增面试方向提案；P0-15 branch protection 属 GitHub 网页操作，不产文件（作者手工） |
| P0-01/P0-12 两个 Boot/Maven 认知坑（本次评审实测） | 无（上游没踩到这一层） | 无 | ① **Maven POM 里显式写的 `<configuration>` 值优先于 `-D` 用户属性**：根 pom 直写 `<excludedGroups>docker</excludedGroups>` 会让 CI 的 `-DexcludedGroups=` 失效，集测 0 个测试仍报 BUILD SUCCESS（已用两次反向实验证实）→ 排除项必须走属性 `${annona.tests.excluded}`，且 CI 要断言 `Tests run` 非零。② **`@ConditionalOnBean` 只能用在自动配置类上**：普通 `@Configuration` 的求值早于 autoconfig 注册 bean 定义，条件永远为假（已导致 `FlywayExtensionGuard` 静默失效）→ 用 `@ConditionalOnProperty` + `ObjectProvider`。③ **surefire 默认不扫 `*IT.java`**，改用 `*IT` 命名集测时必须显式 `<includes>` | 见阶段总结 §5 D17–D21 |
| P1a 各表引用 direction 的方式 | 无（上游无方向字典） | 无 | 业务表方向列一律 `direction_id` 外键到 `direction.id`；**不得存 `key` 字符串**（`key` 只在 owner 内唯一，存字符串无法定位归属）。见 `specs/2026-09-25-direction-master-data-adr.md` 修订记录 |
| P1b-10 模型 Key 与额度 | `🅖 modules/llmprovider/service/{ApiKeyEncryptionService,LlmProviderConfigService,LlmProviderBootstrapService}.java`、`modules/llmprovider/dto/{ProviderDTO,AsrConfigDTO,TtsConfigDTO}.java`、`common/ai/{LlmProviderRegistry,ApiPathResolver,LlmEmbeddingConfig}.java`（+`common/config/LlmProviderProperties.java`）、`🅢 src/lib/{usage.ts,model-pool.ts,deepseek.ts}`、`prisma/schema.prisma::TokenUsage` | AES/GCM + nonce 结构、masked 字段形态、Provider 连通性测试、模型池 LOW 档降级思路 | **不抄 `DEV_FALLBACK_KEY`**（ADR 已定）；Key 改为五用途拆分；记账字段补 `prompt_hash/evaluator_version` |

### B. 后端·知识与面试链路（对应 P1a / P1b）

| 任务 | 扫描路径 | 借鉴什么 | 必须改什么 |
|---|---|---|---|
| P1a-01/02 身份 | `🅢 src/lib/{auth.ts,auth-client.ts,auth-utils.ts}`、`scripts/reset-password.ts`（scrypt 参数）、`prisma/schema.prisma::{User,Account,Session}` | 会话字段形状、密码重置脚本形态 | 上游是 NextAuth：`Account`（OAuth）v1 不要；会话改 Redis（`🅖 infrastructure/redis/InterviewSessionCache.java` 可看缓存结构），`user_session` 降为审计投影 |
| P1a-04 采集 | `🅢 src/components/focus-room/{focus-timer,pomodoro-station,long-press-exit}.tsx`、`src/app/(dashboard)/checkin/actions.ts`、`src/lib/study-stats.ts` | 番茄钟状态机、长按退出防误触、统计口径（连续天数/日均） | **质量分级 `quality` 上游完全没有**，只借交互与字段形状，判定逻辑自写并有测试 |
| P1a-05/06 文档入库与分块 | `🅖 infrastructure/file/{DocumentParseService,TextCleaningService,FileHashService,FileValidationService,ContentTypeDetectionService,DocumentParseProperties}.java`、`modules/knowledgebase/service/KnowledgeBase{Parse,Upload,Vector,Persistence,Delete,List,Count}Service.java`、`modules/knowledgebase/listener/Vectorize{StreamProducer,StreamConsumer,RecoveryScheduler}.java`、`🅢 src/lib/rag/chunk.ts`、`tests/rag-chunk.test.ts` | 文件校验与 hash 幂等、向量化状态机与进度字段、**分块死循环兜底与测试用例清单**（`🅢` 的测试是最省事的验收清单） | TS → Java 重写分块算法；新增 `analyzer_version` 与中文分词入库 |
| P1a-07 混合检索 | `🅜 knowledge/service/{HybridSearchService,RerankService,CosineRerankFallback,DashScopeRerankService,ElasticsearchVectorStore,MilvusVectorStore}.java`、`knowledge/flow/{RagRetrievalNode,RetrievalGraderNode,WebSearchNode}.java`、`🅖 modules/knowledgebase/repository/VectorRepository.java`、`service/KnowledgeBase{QueryService,QueryProperties,VectorService,VectorProperties}.java`、`RagQueryExecution.java`、`🅢 src/lib/rag/{retriever,rerank,search,client}.ts`、`tests/rag-rerank.test.ts` | RRF 融合与重排降级顺序、相似度阈值与 TopK 自适应、联网兜底触发条件、**grader 节点判定“够不够”的阈值** | ES/Milvus 通道改为 PG `tsvector` + `pg_trgm`（存储 ADR）；重排在 v1 用余弦，rerank 模型进扩展规划 |
| P1a-08 流式问答 | `🅖 modules/knowledgebase/{RagChatController,service/RagChatSessionService}.java`、`infrastructure/mapper/RagChatMapper.java`、`app/src/main/resources/prompts/knowledgebase-query-{system,user}.st`、`knowledgebase-query-rewrite.st`、`🅢 src/lib/ai/`与 `src/app/api/{chat/messages,conversations}/**` | SSE 事件粒度、引用结构、查询改写 prompt | 净化策略对齐 `🅢 src/components/ai/markdown-sanitize-schema.ts` + `tests/markdown-sanitize.test.ts` |
| P1a-09 评测 | `🅜 scripts/rag-eval/{eval,compare,diagnose}.py`、`queries.json`、`README.md`；`🅢 scripts/{verify-pgvector.ts,verify-rerank.ts,normalize-embeddings.ts}` | 指标定义（Recall@K/MRR）、query 集格式、对比报告的列设计、向量归一化校验脚本 | 改成打 annona 接口；输出进 `retrieval_eval_run` 表与 `docs/benchmarks/` |
| P1b-01 SKILL 机制 | `🅖 modules/interview/skill/{InterviewSkillService,InterviewSkillController,InterviewSkillProperties}.java`、`app/src/main/resources/skills/<方向>/{SKILL.md,skill.meta.yml}`、`skills/_shared/`、`prompts/interview-question-skill-{system,user}.st` | SKILL 加载/注册/热更新、`_shared` 公共片段复用、`skill.meta.yml` 展示元数据 | front-matter 增加 `key/parent/origin` 与 `direction` 表对齐；方法论型 SKILL（结构化/STAR）上游没有，自写 |
| P1b-02/03 出题与容量 | `🅖 modules/knowledgebase/service/{KnowledgeBaseQuestionService,KnowledgeBaseQuestionGenerationService,QuestionGenerationStateService,KnowledgeBaseInterviewService}.java`、`model/{KnowledgeBaseQuestionEntity,KnowledgeBaseQuestionFollowUpDTO,QuestionGenerationConfig,QuestionGenStatus,QuestionGenStatusResponse,KnowledgeBaseInterviewCapacityRequest/Response}.java`、`listener/QuestionGen{StreamProducer,StreamConsumer,RecoveryScheduler}.java`、`prompts/knowledgebase-question-generation-{system,user}.st` | 主问题+追问的存储形状、草稿保留与缺口提示、**追问数硬约束的容量校验算法** | 新增 `difficulty` 与 `rubric` 落库、`hit_rate` 回写标定（上游无） |
| P1b-04/05 组卷与状态机 | `🅖 modules/interview/service/{InterviewSessionService,InterviewQuestionService,InterviewPersistenceService,InterviewHistoryService,AnswerEvaluationService}.java`、`model/{InterviewSessionEntity,InterviewSessionDTO,InterviewQuestionDTO,HistoricalQuestion,SubmitAnswerRequest}.java`、`infrastructure/redis/InterviewSessionCache.java`、`🅜 interview/application/**`（重点 `finalize`、`guard/singleflight/**`：`FlightReplayLocalCache`、`FlightResultSerializer`、`DistributedInterviewAiSingleFlightService`、`InterviewAiSingleFlightConfiguration`）、`admin/src/main/resources/workflow/面试提问官.yml` | 会话快照冷热分层、**交卷 finalize 的幂等与结果重放语义**、工作流 YAML 里的追问状态定义 | SingleFlight 只用于**读合并**；写路径用 `session_id + evaluator_version` 幂等键 |
| P1b-06/07 评估与可比 | `🅖 common/ai/StructuredOutputInvoker.java`、`StructuredOutputProperties.java`、`common/evaluation/{UnifiedEvaluationService,EvaluationReport,QaRecord,InterviewEvaluationProperties}.java`、`prompts/interview-evaluation-{system,user,summary-system,summary-user}.st`、`modules/interview/service/AnswerEvaluationService.java` | 结构化输出重试的**错误信息回填方式**、分批评估 + 二次汇总结构、降级兜底分支 | 必加 `evaluator_version/prompt_hash` 留痕与难度加权（上游无，是 annona 的硬需求） |
| P1b-08 简历 | `🅖 modules/resume/**`（`Resume{Upload,Parse,Analysis,Grading,Persistence,Delete,History}Service`、`listener/Analyze*`、`ResumeAnalysisRecoveryScheduler`）、`prompts/resume-analysis-{system,user}.st`、`jd-parse-system.st` | 解析→分析→报告的分层与失败重试 | 简历字段入 `direction`（JD 派生方向）而非自由文本 |
| P1b-09 PDF 导出 | `🅖 infrastructure/export/PdfExportService.java` + `app/src/main/resources/fonts/ZhuqueFangsong-Regular.ttf` | 中文字体嵌入与排版结构 | **字体许可证先核对再决定继用还是换 Noto**；报告内容需包含决策理由节 |

### C. 后端·语音与编排（对应 P3 / P4）

| 任务 | 扫描路径 | 借鉴什么 | 必须改什么 |
|---|---|---|---|
| P3-01~05 语音全链路 | `🅖 modules/voiceinterview/**` 全套：`handler/VoiceInterviewWebSocketHandler.java`、`service/{QwenAsrService,QwenTtsService,DashscopeLlmService,VoiceInterviewService,VoiceInterviewPromptService,VoiceInterviewEvaluationService}.java`、`context/{VoiceContextCompressor,VoiceHistoryLoader}.java`、`config/{WebSocketConfig,VoiceInterviewProperties}.java`、`dto/WebSocket{ControlMessage,SubtitleMessage}.java`、`resources/voice-interview-opening.yml`、`prompts/voice-interview-context-summary.st`；前端 `🅖 frontend/src/components/{AudioRecorder,AudioPlayer,RealtimeSubtitle}.tsx`、`utils/{voiceInterview.ts,voiceWebSocketUrl.ts}`（含测试）、`public/audio-worklet/pcm-processor.js`；`🅜 src/components/audio/*`、`src/hooks/audio/*` | **这是最值得整扫的一套**：句级并发 TTS 调度、VAD 断句、回声防护与手动提交、开场白配置化、字幕事件协议、AudioWorklet 采集 | 延迟埋点改为端到端预算表口径（P3-06）；ASR/TTS 走 `ModelProvider` 抽象而非直连 DashScope |
| P4-01/02 日程 | `🅖 modules/interviewschedule/**`（`InterviewParseService`、`InterviewScheduleService`、`ScheduleStatusUpdater`、`InterviewStatus`）、`prompts/jd-parse-system.st`；前端 `🅖 frontend/src/pages/InterviewSchedulePage.tsx`、`components/interviewschedule/*`、`api/interviewSchedule.ts`、`hooks/useInterviewSchedule.ts`（依赖 `react-big-calendar`） | 规则+AI 双引擎解析的字段抽取与冲突处理、状态过期定时任务、日历交互 | 邀请原文不入库只存哈希（隐私）；突击入口需调 `planner`（上游无） |
| P4-04/05 Agent | `🅢 src/lib/agent/{runtime,service,types,prompts,decisions,report,weekly,index}.ts`、`src/lib/tools/{agent-tools,rag-tools,study-tools,todo-tools,index}.ts`、`src/app/api/agent/**`（runs/decisions/approval/cancel/cron）、`src/lib/memory.ts`、`notification.ts`、`prisma/schema.prisma::{AgentRun,AgentStep,AgentToolCall,AgentDecision,AgentApproval,AgentSchedule,UserMemory,Notification}` | 留痕表形状、**approval 门机制**、工具注册与参数校验、周报/日报去重 | TS 异步循环 → Java 需显式线程池与超时；记忆注入默认关，P5 才开 |

### D. 前端 UI（**重点：设计、交互与组件结构全部从这里借**）

| 面板/组件 | 扫描路径 | 借鉴什么 | 迁移注意（Next → Vite） |
|---|---|---|---|
| 全局风格与基础件 | `🅢 src/components/ui/*`（button/card/dialog/sheet/tabs/sonner/badge…）、`src/app/globals.css`、`src/styles/{markdown,onboarding,studio,studio-theme,accordion-gallery,bg-accordion}.css`、`components.json`、`src/components/theme-provider.tsx`、`src/styles/ui/variable-proximity.{css,tsx}` | shadcn 基座与主题变量、**鼠标邻近动效**、markdown 样式表 | 组件基本可直接重写；`next-themes` 换本地 `useTheme`（参考 `🅖 frontend/src/hooks/useTheme.ts`） |
| 3D 学习小岛 | `🅢 src/components/landing/learning-island.tsx`（**已实测使用 `@react-three/fiber` + `drei` 的 Canvas/useFrame/MathUtils**）、`landing/learning-island-dynamic.tsx`（懒加载包装）、`island/scene-selector.tsx`、`src/lib/scene-meta.ts`、`package.json`（`three@^0.185.1`/`@react-three/fiber@^9.7.0`/`drei@^10.7.7`） | 场景搭建与相机控制、生长动画驱动变量、**懒加载与降级写法**、场景元数据结构（方向→植物映射） | `next/dynamic` → `React.lazy`；去 `'use client'`；资源改 `public/models/`；移动端 2D 降级需自写 |
| 沉浸模式与环境音 | `🅢 src/components/focus-room/{rainforest-focus-room,pomodoro-station,focus-timer,long-press-exit}.tsx`、`dashboard/ambient-sound.tsx`、`layout/{scene-overlay,background-video}.tsx`、`src/lib/quotes.ts`、`public/{rain.mp3,rain.mp4,snow.wav}` | 全屏无干扰布局、离开自动静音、鼓励语轮换、长按退出防误触 | 音频资源需重压缩与授权核对（上游有 `scripts/optimize-backgrounds.mjs` 可参考做法） |
| 打卡与热力图 | `🅢 src/components/calendar/heatmap.tsx`、`statistics/stats-summary.tsx`、`profile/{focus-duration-chart,profile-header,yubao-energy}.tsx`、`src/app/(dashboard)/statistics/page.tsx`、`checkin/{page,checkin-button,footprint-heading}.tsx`、`src/lib/study-stats.ts` | 热力图格子绘制与 tooltip、统计卡片层级、**连续天数文案形状** | 图表库：上游自绘 SVG，annona 统一用 `recharts`（`🅖` 已用 `recharts@^3.6.0`），雷达图直接借 `🅖 frontend/src/components/RadarChart.tsx` |
| 计划与文档工作室 | `🅢 src/components/plans/*`、`studio/{markdown-studio,editor-pane,ai-chat-panel,outline-panel,accordion-gallery,bg-accordion-gallery,use-studio-theme}.tsx/ts`、`src/lib/studio/{outline,plan-serialize,plan-sync,find}.ts`、`src/lib/{plan-split,plan-tasks,use-todos}.ts`、`src/app/(dashboard)/plans/[id]/studio/{page,studio-client}.tsx`、`docs/[id]/doc-studio-client.tsx` | **MD→任务拆分的输入输出形状**、大纲面板、编辑器与 AI 面板共存布局、模板库交互 | 上游 `actions.ts` 是 Next Server Actions，Vite 下无对应 → 必改成 REST；`src/app/**/actions.ts` 一律不可当组件参考 |
| 面试界面 | `🅖 frontend/src/pages/{InterviewHubPage,InterviewPage,InterviewHistoryPage,HistoryPage,KnowledgeBaseInterview*Page,UploadPage,SettingsPage}.tsx`、`components/{InterviewChatPanel,InterviewPanel,InterviewDetailPanel,InterviewMessageBubble,InterviewPageHeader,AnalysisPanel,ScoreProgressBar,UnifiedInterviewModal,CodeBlock,HistoryList}.tsx`、`constants/{routes,knowledgebaseInterview}.ts`、`types/{interview,resume,llmProvider}.ts` | 面试中的信息密度控制（题干/倒计时/追问层级）、历史列表筛选、评分展示与进度条、Markdown+代码块渲染 | 上游 `pages/*.test.ts` 与 `utils/*.test.ts` 是**行为规格**，先读它们反推验收用例 |
| 知识库与上传 | `🅖 frontend/src/pages/{KnowledgeBaseUploadPage,KnowledgeBaseManagePage,KnowledgeBaseQueryPage}.tsx`、`components/batch-upload/*`、`api/{batchUpload,knowledgebase,ragChat,stream}.ts`、`hooks/{useBatchUpload,useBatchStatusPolling}.ts` | 批量上传队列 UI、**轮询退避策略**、进度与失败重试展示 | 轮询改 SSE（annona ETL 进度已有 SSE 通道），不要保留轮询 |
| 登录与新手引导 | `🅢 src/components/auth/auth-scene-shell.tsx`、`onboarding/{onboarding-provider.tsx,tour-steps.ts}`、`src/styles/onboarding.css` | 新手引导游标交互与文案节奏 | 引导步骤必须改为 annona 真实入口（方向选择/知识库/面试/面板） |
| 头像与个人 | `🅢 src/components/{profile/avatar-picker,ui/app-avatar,ui/avatar}.tsx`、`src/lib/{avatar-compress,avatar-presets}.ts` | 前端压缩与预签名直传配合、预设头像库 | 上传大小与尺寸限制入配置，不写死 |
| 在线共学 | `🅢 server/{room.ts,protocol.ts,index.ts}`（Node WS 房间与广播协议） | **只借“在线人数/他人专注状态”的数据形状** | **不实现房间协议**：改 Redis ZSET + TTL + 10s 轮询只读接口 |
| 落地页与动效 | `🅢 src/components/landing/{hero,features-grid,how-it-works,checkin-showcase,studio-showcase,cta-section,footer,ClickSpark,ShinyText,SplitText}.tsx`、`🅜 src/components/marketing/*`、`src/layouts/*`、`src/components/interview/{intro,report,sketchpad}/*` | 首屏叙事顺序（先主线后功能）、shiny/split 文字动效、CTA 布局 | 不要拉入重型动画库；上游用 `framer-motion`，annona 可用 CSS 完成大部分 |

### E. 上游**根本没有**的部分（不要浪费时间扫描，全部自写）

| 能力 | 说明 |
|---|---|
| `direction` 方向主数据与外键约束 | 上游两侧都是自由文本/无约束（见 `specs/2026-09-25-direction-master-data-adr.md`） |
| 学习数据质量分级（心跳与 VERIFIED/PARTIAL/SELF_REPORTED） | 上游只有前端计时，无服务端心跳与可信度概念 |
| 掌握度与遗忘曲线、规则链、**保护规则** | 完全原创；扫描 `🅢 src/lib/agent/decisions.ts` 只为参考留痕字段形状 |
| 可解释面板与决策反驳 | 无上游对应（`🅢 src/components/agent/decision-timeline.tsx` 可参考时间线呈现） |
| 评分可比性（难度加权 + 模型/Prompt 版本留痕） | 上游评估直接给分，无可比区间概念 |
| 错题沉淀回知识库、同题多模型对照 | 原创 |
| Key 五用途拆分与 KEK 轮换 | 上游按 Provider 存一份 |
| 离线 A/B（决策选题 vs 随机选题） | 可复用 `🅜 scripts/rag-eval` 的**脚本骨架**，实验设计自写 |

---

## P0 骨架与门禁（10.5 人日，已完成 P0-01 至 P0-14，详见 [P0 阶段总结](./reports/P0-骨架-阶段总结.md)）

### 批次划分（开发按批做，不按单任务做）

单任务逐个交付会把大量时间花在“改一点跑一遍全量验证”上。按**耦合度与共同验收手段**分五批，每批一次做完、一次自检：

| 批次 | 包含任务 | 人日 | 为什么必须同批 | 共同验收 |
|---|---|---|---|---|
| **B1 模块切形** | P0-02 / 03 / 04 / 07 | 2.5 | `packaging=pom` 与 `src/` 迁移不同批会断构；ArchUnit 与“spi 零 Spring”校验依赖模块存在 | `mvn verify` 绿 + 四 jar 产出 + 故意违规 import 能红 |
| **B2 启动与基线** | P0-05 / 06 / 08 | 2.5 | 三者都靠“看启动日志与失败文案”验收，开一次应用能验完三件 | 三类启动场景实测（无 KEK / dev / 无扩展） |
| **B3 前端骨架** | P0-09 | 1 | 工具链与失败模式与 Java 侧完全无关 | `pnpm build` 产物进 static，首页可访问 |
| **B4 交付物** | P0-10 / 13 | 1.5 | 本机不跑，只能一起写一起交给 CI 验 | 文件写完 + CI compose job 绿 |
| **B5 门禁** | P0-11 / 12 / 14 | 2.5 | hooks、workflow、模板是同一套门禁的三个面 | 能拦中文 subject、能拦假 Key、CI 能红 |

P0-15（仓库设置）不计批次，需你在 GitHub 网页操作。

**开工顺序**：B1 → B2 → B3 → B4 → B5（B3/B4 无相互依赖，可换序）。

**目标**：仓库结构、依赖方向、CI/CD 与本地一键跑通全部到位，后续每个阶段只需往里填业务。
**为什么先做门禁**：`AGENTS.md` 承诺的 commit hook、密钥扫描、ArchUnit、SPI 发布流水线在 P0 之前**一条都不存在**。规范先行于实现是本项目最大的风险，必须在写业务代码前补齐。

| ID | 任务 | 验收（必须贴命令输出） | 人日 | 依赖 |
|---|---|---|---|---|
| P0-01 | 根 pom 建立**依赖与插件治理**：`dependencyManagement`（`spring-ai-bom` 2.0.0）、`pluginManagement`（enforcer 版本）、enforcer 四条规则（JDK 21+、Maven 3.9+、`requireUpperBoundDeps`、**禁用 MySQL/Mongo/ES 依赖坐标**）、surefire 默认 `excludedGroups=docker` + `--enable-native-access`。**`packaging` 暂留 jar**：聚合器不能持有 `src/`，`packaging=pom` 与源码迁移必须同在 P0-02，否则中途断构 | ① `mvnw -B -q verify` `EXIT=0`；② 临时塞入 `com.mysql:mysql-connector-j` 后 `mvnw validate` **必须失败**（已实测 `EXIT=1`）；③ `dependency:get` 能解析 spring-ai 2.0.0（已实测，无需 milestone 仓库） | 1 | — |
| P0-02 | 根 pom 改 `packaging=pom` + `<modules>`；建 `annona-common`、`annona-spi`、`annona-infrastructure`、`annona-server` 四模块，把现有 `src/` 迁入 `annona-server` | `mvn -q verify` 全绿，四个 jar 产出 | 1 | P0-01 |
| P0-03 | 统一响应与异常：`annona-common` 只放 `Result<T>`、`ErrorCode`、`BusinessException`（**零框架依赖**）；`GlobalExceptionHandler` 落在 `annona-server/config/web`。状态码策略：业务失败 HTTP 200 + `Result.error`，路由/传输层错误（404/405/400/500）返回真实状态码 + 同一 `Result` 体 | ① `/api/meta/ping` 返回带非空 `traceId` 的 `Result`；② 抛 `BusinessException` → 200 + code；③ 不存在的路径 → 404（不是 200）；④ `annona-common/pom.xml` 无 `org.springframework*` | 0.5 | P0-02 |
| P0-04 | `annona-spi`：五个扩展点接口骨架（`IdentityProvider` `ModelProvider` `Retriever` `LearningSignalReader` `DecisionRule`）+ 跨模块契约 DTO，**零编译期依赖**（不依赖 common：spi 对 common 的 import 实测为 0） | ① 模块 pom 无任何 compile 依赖；② ArchUnit 规则通过；③ enforcer `bannedDependencies` 已接（挡住**传递**依赖，ArchUnit 只看得见 import）：`dependency:tree` 无 `org.springframework` | 0.5 | P0-02 |
| P0-05 | `config/` 六包骨架（web/async/persistence/security/properties/observability）+ 四类线程池 + `@ConfigurationProperties` 分组（`annona.*`） | 启动日志打印四类池参数；`application.yml` 无散落业务配置 | 1 | P0-03 |
| P0-06 | Flyway `V1__baseline.sql`：`CREATE EXTENSION vector/citext` + 身份 7 表（设计文档 §5.3）+ `direction` 主数据表 | 空库启动自动建表；二次启动 skip；`ddl-auto: validate` 下应用可启动 | 1 | P0-05 |
| P0-07 | ArchUnit 七条结构规则（项目结构 §10），白名单机制 + `shared/` `modules/` 包骨架 + `package-info.java` | 故意让 `modules/*` import `infrastructure/*` → `mvn test` 失败 | 0.5 | P0-04 |
| P0-08 | `StartupValidator`：缺 `ANNONA_SECRET_KEY`（prod profile）即启动失败；缺 pgvector/citext 扩展时给出可执行提示 | 三种场景实测：prod 无 KEK → 拒绝启动；dev → 正常；无扩展 → 明确报错文案 | 0.5 | P0-06 |
| P0-09 | `annona-web`：Vite + React + TS + Tailwind4 骨架、Axios 单实例、路由与四平级入口布局、构建产物拷贝进 server `static` | `pnpm build` 后访问 `localhost:8080` 出首页；OpenAPI 类型生成脚本可跑 | 1 | P0-03 |
| P0-10 | `docker/`：多阶段 Dockerfile、`docker-compose.yml`（PG+Redis+MinIO+server+web）、`compose.dev.yml`、`postgres/init.sql` | **本机不跑**：文件写完即可，实际启动验证由 CI compose job 完成（全新机器一条命令到首页 200） | 1 | P0-06,P0-09 |
| P0-11 | `.githooks/`：commit-msg（Conventional Commits + 英文校验）、pre-commit（gitleaks）；`core.hooksPath` 由 `make setup` 配置 | 中文 subject 与 `update` 类消息被拒；写一个假 Key 进文件被拦 | 0.5 | — |
| P0-12 | `.github/`：`ci.yml`（unit+ArchUnit / `services:` 跑 pgvector+redis 集测 / compose 冒烟 / 前端 / gitleaks 五个 job + `gate` 汇总 job）、`e2e.yml`（Playwright 容器 job）、`rag-eval.yml`、`release.yml`、`publish-spi.yml`、`stale.yml`、`CODEOWNERS`、ISSUE/PR 模板、FUNDING（**dependabot 已移出 P0**，见阶段总结 D16） | 按 `specs/2026-09-25-dockerless-local-dev-adr.md` 的 **CI 执行矩阵**建 job；集测 job 必须带“Tests run 非零”断言（否则 0 测试会假绿）；故意提交一个失败断言确认能红 | 1.5 | P0-07,P0-11 |
| P0-13 | `Makefile`：`setup / up / dev / test / eval / logs / reset / quickstart`；`quickstart` = 起中间件 → 迁移 → seed → 打印地址与演示账号 | **在 CI/容器环境验证**（本机无 Docker，只验 `make` 语法与目标存在） | 0.5 | P0-10 |
| P0-14 | 仓库门面。**已完成**：`README.md`（含状态横幅）、`.env.example`、`LICENSE`（AGPL-3.0 FSF 原文逐字复制，已校验 661 行）、`SECURITY.md`、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`、`.editorconfig`。**剩余**：`.github/ISSUE_TEMPLATE` 与 PR 模板文案细化（属 P0-12 产出的一部分） | 新同事只读 README 能跑起来（真找一人验证） | 0.5 | P0-12 |
| P0-15 | 仓库设置（需在 GitHub 网页/API 做，不产生文件）：branch protection 将集测与 compose job 设为**必需检查**、建 `good-first-issue`/`skill-proposal` 标签、填仓库描述与 topics。（~~开启 Dependabot alerts~~ 已推到 P1b-10，见阶段总结 §5 D16） | 未过 CI 的 PR 无法合并；`gh api` 或设置页截图存档到阶段总结 | 0.5 | P0-12 |

**出口条件**：① `mvn -q verify` 绿且 ArchUnit 七条生效（**本机验证到此为止**）；② 全新机器 `docker compose up -d` + `make quickstart` 到首页 200（**由 CI 验证并留存日志链接**，本机无 Docker 不跑）；③ 五个 SPI 有骨架与 Fake 实现；④ hook 与 CI 能拦截违规提交；⑤ `/api/meta/ping` 与一次模型连通性测试通过（可在 CI 或本机自备的 PG/Redis 环境）；⑥ `docs/reports/P0-骨架-阶段总结.md` 已写。

---

## P1a 数据与知识底座（12 人日）

**目标**：学习行为开始被采集（带质量分级），知识文档能入库、能被检索、能流式问答。
**前置**：P0 出口全部满足。

| ID | 任务 | 验收 | 人日 | 依赖 |
|---|---|---|---|---|
| P1a-01 | `identity` 模块：注册/登录/登出/会话、Redis 会话（7 天滑动）、`user_profile`、`login_attempt` 锁定、scrypt 编码器与透明重哈希 | 注册→登录→改密→旧口令失效；10 次失败登录被锁；`user_session` 写失败不影响登录 | 2 | P0-06 |
| P1a-02 | `IdentityProvider` 三实现：`local` / `platform` / `none`（单机 bootstrap `id=local`） | 三种 mode 下同一套业务代码都能跑；none 模式无登录页直达首页 | 1 | P1a-01 |
| P1a-03 | `direction` 字典服务 + 方向选择器组件（下拉 + 即时新建 + 升级为绑定知识库） | 新建方向即落库；`USER_CUSTOM` 可绑 `kb_doc_id`；有历史数据的方向只能归档不能删 | 1.5 | P0-06 |
| P1a-04 | `study` 采集：打卡、番茄钟、`study_session` + `study_event`、服务端心跳与质量分级（VERIFIED/PARTIAL/SELF_REPORTED） | 挂机 30 分钟无心跳 → 标 PARTIAL；手动补录 → SELF_REPORTED 且不进决策计算（有测试） | 2 | P1a-03 |
| P1a-05 | `knowledge` 写侧：上传→S3→Tika 解析→结构感知分块→内容 hash 幂等→Embedding 批处理→状态机 + 进度 SSE | 上传 PDF 与 DOCX 各一篇，READY 后能看到分块；重复上传零 token 消耗 | 2.5 | P1a-01 |
| P1a-06 | 分块器纯逻辑实现 + 单测（死循环兜底、段落边界、重叠滑窗、上限保护） | `chunk` 包覆盖率 ≥85%，golden 快照入库 | 1 | P1a-05 |
| P1a-07 | `retrieval`：语义通道（HNSW）+ 关键词通道（应用层分词 + `simple` + `pg_trgm`）+ RRF + 余弦重排；`PgVectorRetriever` 实现 SPI | 检索测试接口给出命中与分数；改 `annona.retrieval.backend` 不报错（Fake ES） | 2 | P1a-05 |
| P1a-08 | `qa`：SSE 流式问答、会话管理、源引用追溯、Markdown 净化渲染 | 一次提问，前端逐字输出且引用可点击跳回原文段落 | 1.5 | P1a-07 |
| P1a-09 | `scripts/rag-eval` + `docs/tests/指标测试-检索.md`：Recall@K / MRR 基线，纯向量 vs 混合对比 | 出报告（真实数字），`retrieval_eval_run` 有记录；结论写进 `docs/benchmarks/` | 1 | P1a-07 |

**出口条件**：① 真实资料入库后可流式问答并显示引用；② 心跳与质量分级有单测与实测证据；③ 混合检索相对纯向量的 Recall@K 提升**有实测数字**（若为负，按 ADR 触发条件重开检索方案讨论）；④ `chunk` 包覆盖率达标；⑤ 阶段总结已写。

---

## P1b 面试与评估（14 人日）

**目标**：两个方向能完成一场面试——内置技术方向、知识库派生方向；评估分数可比。

| ID | 任务 | 验收 | 人日 | 依赖 |
|---|---|---|---|---|
| P1b-01 | `interview/skill`：SKILL.md 解析、注册表、内置技术方向 10+、方法论方向（结构化/STAR/项目深挖） | 目录放一个 `.md` 即被识别，无需改代码；缺字段有明确报错 | 2 | P1a-03 |
| P1b-02 | `questionbank/generate`：从知识库生成主问题+参考答案+关键点+评分标准+追问；异步出题、不足留草稿、实际/目标追问数提示 | 一篇讲义 → 生成 10 题带评分标准；不足时保留草稿并提示缺口 | 2.5 | P1a-05 |
| P1b-03 | 题库维护 + 容量校验（追问数为硬约束，不足禁用选项 + 后端兜底） | 容量不足的选项在前端禁用且后端二次拦截 | 1.5 | P1b-02 |
| P1b-04 | `interview/orchestrator`：组卷（方向配额→难度→阶段→主问题+追问）、历史题目去重（向量+关键词双判） | 连续两场不出现重复题干；配额受外部传入的 `InterviewPlan` 控制（先给静态计划） | 2 | P1b-01,P1b-03 |
| P1b-05 | 状态机与中断续面（Redis 热缓存 / DB 冷存储恢复）、面试中心、逐题作答与交卷 | 中途杀进程重启可继续；交卷幂等（重复提交不产生双份评估） | 1.5 | P1b-04 |
| P1b-06 | `evaluation`：分批评估 + `StructuredOutputInvoker` 统一重试 + 二次汇总 + 降级兜底 | 构造 3 种畸形输出，均落 `fallback_used` 且逐题原文保留 | 2 | P1b-05 |
| P1b-07 | `evaluation/comparability`：难度加权总分、`chat_model/evaluator_model/prompt_hash/evaluator_version` 留痕、可比区间判定 | 换模型后趋势图自动断开并提示原因（有测试） | 1.5 | P1b-06 |
| P1b-08 | 简历模块：上传解析、AI 分析报告、重复检测、失败重试（Redis Stream） | 一份 PDF 简历 → 报告 + 可用作面试上下文 | 1.5 | P1a-05 |
| P1b-09 | `infrastructure/export`：PDF 评估报告与简历报告异步导出（内置中文字体、雷达图、逐题评价） | 一份报告 PDF 内容与页面数据一致，中文不乱码 | 1.5 | P1b-07 |
| P1b-10 | `usage/metering` + `key`：token 记账（不进事务）、每日配额与超额熔断、Key 五用途加密与掩码 | 超额后被拦且返回可理解文案；`maskedApiKey` 全链路无明文；日志无 Key | 2 | P1b-04 |

**出口条件**：① 内置方向与知识库派生方向各完成一场面试并导出报告；② 交卷重复提交幂等；③ 评估降级有测试覆盖；④ 换模型后趋势图断开的行为可复现；⑤ 每场面试的 token 成本可查；⑥ 阶段总结已写。

---

## P1c 训练决策层（10 人日）——**本项目的存在理由**

**目标**：面试不再是随机出卷，且系统能解释自己为什么这么出。

| ID | 任务 | 验收 | 人日 | 依赖 |
|---|---|---|---|---|
| P1c-01 | `shared/signal`：`LearningSignalReader` 实现，聚合时长(带质量)、完成率、逐题得分、间隔天数 | 同一用户同一天的信号快照可复现（golden 测试） | 1.5 | P1a-04,P1b-07 |
| P1c-02 | `planner/mastery`：遗忘衰减 + 练习增益 + 置信度（公式见设计文档 §6.2），纯函数 + 单测 | 覆盖率 ≥85%；边界用例（首次练习、满分、全错、30 天不练）全部断言 | 2 | P1c-01 |
| P1c-03 | `planner/rule`：规则链（FORGETTING_CURVE / WEAK_DIRECTION / SAMPLE_GUARD / CAP_RATIO / VERSION_BASELINE），每条一个 `DecisionRule` 实现且可独立开关 | 关掉任意一条，其余仍产出合法计划 | 2 | P1c-02 |
| P1c-04 | `planner/guard`：样本量 <3 不调难度、全 SELF_REPORTED 则退化为均匀出卷、单方向 ≤40%、换模型后前 3 场只采基线 | 四种降级场景各有测试，且降级原因被写进输出 | 1.5 | P1c-03 |
| P1c-05 | `decision_trace` 落库 + `interview/orchestrator` 接 advisor（唯一同步跨模块调用）+ 复习题掺入组卷 | 面试前删掉 trace 表能观察到组卷退化（说明确实由决策驱动） | 1 | P1c-03,P1b-04 |
| P1c-06 | 可解释面板（前端 `explain-panel`）：把 `reasons` 翻成人话，显示数据质量等级与"数据不足"状态 | 三条真实决策可被非项目成员读懂；SELF_REPORTED 明确标注 | 1.5 | P1c-05 |
| P1c-07 | 决策反驳（`rejected_by`）+ 规则降权 + 反哺复习型任务到 `plan/todo` | 同一规则被驳 3 次后自动停用并留痕；复习任务带理由 | 1 | P1c-06 |
| P1c-08 | `annona demo seed --weeks 6` 合成历史数据 + 离线 A/B 脚本（决策选题 vs 随机选题）+ 报告 | 脚本可复现；得分差或复习命中率有数字；结论进 `docs/benchmarks/` | 2 | P1c-06 |

**出口条件（P1 全阶段出口）**：① 面板能对最近 5 场面试给出可核对的理由；② 四条保护规则可被测试触发；③ 反驳路径能改变后续决策；④ A/B 报告有真实数字（**若决策组不优于随机组，必须公开写进 README 并说明修正方向，不许藏着**）；⑤ `docs/reports/P1-闭环内核-阶段总结.md` 已写，且设计文档已同步所有偏离。

---

## P2 自习室体验与留存（12 人日）

**目标**：让人愿意每天打开。全部是数据的可视化，不改采集语义。

| ID | 任务 | 验收 | 人日 |
|---|---|---|---|
| P2-01 | 年度热力图 + 专注趋势 + 日均时长 + 方向分布（含质量等级图例，SELF_REPORTED 用不同色） | 与 P1a 数据一致，无重复统计口径 | 2 |
| P2-02 | 3D 学习小岛（连续天数 × 累计时长 × 方向覆盖度驱动生长；不同方向不同植物）+ 懒加载 + 移动端 2D 降级 + `prefers-reduced-motion` | 桌面 60fps / 移动 30fps，实测写进 `docs/tests/指标测试-3D岛屿.md` | 3 |
| P2-03 | 沉浸模式（全屏、仅时间+鼓励语+环境音、离开自动静音） | 切标签页自动暂停音频并记录 BLUR 事件 | 1.5 |
| P2-04 | 主题 token 包（雨林/雪日/暖云）+ 环境音，**以 CSS token 实现，禁止逐页分支** | 新增页面无需为主题写任何代码 | 1.5 |
| P2-05 | 匿名共学状态：Redis ZSET + TTL、只读接口、前端轮询（无房间、无消息通道） | 压测 1k 并发轮询延迟 P95 < 50ms | 1 |
| P2-06 | 计划—任务—打卡联动、今日待办、文档工作室（MD→AI 拆任务）、模板库 | 一份 MD 计划 → 任务生成 → 打卡回写进度 | 2.5 |
| P2-07 | 个人主页与设置页整合（资料、头像历史回滚、模型配置入口、数据导出入口） | 导出能下载到含 identity 全表 + S3 文件的 zip | 1.5 |

**出口条件**：性能指标有实测记录；主题新增页零成本；采集语义未被改动（`study_session` 无新字段）；阶段总结已写。

---

## P3 语音面试（12 人日）

| ID | 任务 | 验收 | 人日 |
|---|---|---|---|
| P3-01 | `voice` WebSocket 握手与协议（上行 PCM / 下行音频块 + 字幕事件）、AudioWorklet 采集 | 端到端打通，弱网断连有明确恢复 | 2 |
| P3-02 | 流式 ASR + 服务端 VAD 断句 + 实时字幕（含中间结果） | 中文连续表达断句准确率可用（实测样本 ≥10 段） | 2 |
| P3-03 | LLM 流式 + 句子级并发 TTS（首句优先，边合成边播） | 首包音频延迟达标（见 P3-06 预算） | 2.5 |
| P3-04 | 回声防护（播放期半双工）+ 手动提交模式 + 暂停/恢复/超时暂停 | 无耳机场景不自我循环；暂停后续面上下文完整 | 1.5 |
| P3-05 | 复用统一评估引擎 + 语音会话多轮上下文 + 开场白可配置 | 语音与文字面试在同一题库下得分可比 | 1.5 |
| P3-06 | **端到端延迟预算表 + P50/P95 实测基线**（停止说话→首包音频），Micrometer 埋点 + `docs/tests/指标测试-语音延迟.md` | 实测不达标则按设计文档降级为"一键朗读答案 + 文字作答"，**不许靠 TTS 首包数字充数** | 1.5 |
| P3-07 | AI 多面试官压力面（单会话扮演 3 角色轮流追问，替代群面） | 一场完整压力面记录 + 评估报告 | 1 |

**出口条件**：端到端延迟有实测分布；语音结果进同一评估引擎且可比；降级路径已实现或被验证不需要；阶段总结已写。

---

## P4 日程与智能体（10 人日）

| ID | 任务 | 验收 | 人日 |
|---|---|---|---|
| P4-01 | 面试邀请解析（规则+AI 双引擎，飞书/腾讯会议/Zoom） | 10 封真实邀请样本 ≥9 封字段正确，失败可手工修正 | 2 |
| P4-02 | 日历日/周/月视图 + 拖拽改期 + 状态流转与定时过期 + 提醒 | 过期任务被定时作业标记，无重复提醒 | 2 |
| P4-03 | 面试前突击入口（日程临近 → 按公司岗位方向生成 10 分钟短模拟，走 planner） | 从邀请到开面一键完成，trace 记录来源为 CRAM | 1.5 |
| P4-04 | `agent`：工具注册表、run/step/toolcall/decision/approval 留痕、关键步骤请求确认 | 待办增删改查可被 Agent 完成且每步可审计 | 2.5 |
| P4-05 | 计划工作流 + 每周学习报告 + 每日总结 + 通知中心（每日去重、MD 渲染）+ 定时调度 + LOW 档降级 | 周报真实引用 P1c 数据；LOW 档省下的 token 有数字 | 2 |

**出口条件**：邀请→日程→突击面试链路通；Agent 每次写操作都有 approval 痕迹；通知不重复；阶段总结已写。

---

## P5 记忆、验证与发布（8 人日）

| ID | 任务 | 验收 | 人日 |
|---|---|---|---|
| P5-01 | 长期记忆（偏好提取与注入）——**仅在已有数周真实数据后启用**，UI 显示每条记忆的来源 | 记忆可逐条删除；无数据来源时不生成 | 2 |
| P5-02 | 错题沉淀回知识库 + 复习闭环再考察 | 答错的题次日能在专项面试中被再次选中且有理由 | 1.5 |
| P5-03 | 同题多模型评分对照 + 误差范围在报告中的公开呈现 | 展示分差，不只展示平均分 | 1 |
| P5-04 | 全链路压测（`scripts/bench`）+ 关键用户路径 e2e 覆盖 + 依赖与密钥审计 | P95 延迟与并发容量进 `docs/benchmarks/` | 1.5 |
| P5-05 | 发布：版本号、changelog、镜像多架构、`annona-spi` 上 Central、README 放 A/B 实验与真实截图 | 陌生人 5 分钟 self-host 成功（真人验证 ≥2 例） | 2 |

**出口条件**：README 首屏的每个数字都有脚本可复现；SPI 可被外部依赖；阶段总结 + 全项目复盘已写。

---

## 计划维护规则

1. 进度登记只有一层：**任务级看本文各阶段任务表**，**阶段级状态看顶部「当前进度」表**；不建任务级 issue，不在任务表里塞 checkbox，阶段 issue 只作为本文的**摘要视图**（不得在 issue 里单独维护一份与此不同的任务列表）。
2. 估时偏差 > 30% 时，在对应阶段小结里写清原因（是漏了什么，还是砍了什么），**不许悄悄挪工作量到下一阶段**。
3. 新增功能必须先回答"是否服务闭环主线"。若否 → 进设计文档 §15 Non-goals 讨论，不进计划。
4. 任何跨 ≥3 模块的改造另开 `docs/plans/<TOPIC>_PLAN.md`，本文档只登记结论与出口条件变化。
5. 砍范围的优先顺序（当时间不够时）：P2 的 3D 岛 → P4 的 Agent → P3 的语音 → **绝不允许砍 P1c 的可解释面板与保护规则**（砍了项目就退化成又一个面试套壳）。
6. 每阶段收尾必须同时产出：代码 + 测试 + `docs/reports/P<n>-<名>-阶段总结.md` + 受影响文档的同步更新（设计文档 / 结构文档 / architecture / ADR）。缺任一项即视为阶段未完成。
7. **借鉴扫描先于实现**：任何任务开工前先查本文「借鉴地图」并读对应路径，在 issue 里留下借鉴说明（§借鉴地图 使用方式第 1 条）。地图里没有的新板块，扫完必须把路径补进地图——**地图不完整本身就是缺陷**。
8. **验收命令的执行环境**：凡验收条涉及 Docker、真实数据库/Redis、浏览器、真实模型调用或压测，默认**在 CI 或部署环境执行并留存日志链接**；本机验收以 `mvn -q verify`（unit + slice + ArchUnit）与前端 `typecheck/build` 为准。不得因本机跑不了而删除、降级或 mock 这类测试。

## 风险登记（计划层面）

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| P1 体量过大导致中途失去反馈 | 高 | 致命 | 已在 P1 内切三个子阶段，各自有可演示出口 |
| 混合检索实测不如纯向量，关键词通道成负资产 | 中 | 中 | P1a-09 早于面试实现做评测；不达标就走 ADR 的重新评估分支 |
| 端到端语音延迟达不到可用 | 中 | 中 | P3-06 前置验证 + 已定义降级路径，不作为 P1/P2 依赖 |
| 决策层在真实数据上表现平平 | 中 | 高 | P1c-08 的 A/B 是诚实检验；若平平则改写宣传语而非伪造对比 |
| 本机无 Docker 导致反馈延迟变大（迁移与检索问题到 CI 才暴露） | 高 | 中 | 纯逻辑与 IO 彻底分离 + `@Tag("docker")` 分层 + CI 集测与 compose 冒烟强制运行；PR 等 CI 绿才合并 |
| 单人开发断档（课业/求职） | 高 | 中 | 每阶段出口物必须独立可演示，断档后能从任何一个出口重新启动 |
| 规范先行但门禁未落地 | 中 | 高 | 全部门禁类任务压在 P0（P0-07/08/11/12），P0 未出口不开 P1 |
