# 年轮 · annona —— 项目结构设计

| 项 | 内容 |
|---|---|
| 文档定位 | 定义仓库与代码的物理结构：Maven 模块划分、包边界、依赖方向、资源与前端目录、命名规范 |
| 关联文档 | [README.md](./README.md)（文档总导航）、[annona-项目设计文档.md](./annona-项目设计文档.md)（产品设计，功能与领域模型以该文档为准）、[architecture/overview.md](./architecture/overview.md)（分层与请求生命周期） |
| 结构流派 | **package-by-feature（按特性垂直切）+ 模块内分层（水平切）**，Maven 多模块聚合，依赖方向严格指向 SPI |
| 参照样板 | Apache HertzBeat / Nacos（多模块聚合 + common/infra 分层）、Spring Boot 官方 starter 约定、阿里 COLA（adapter/app/domain/infrastructure 分层思想） |
| 状态 | v1.0 定稿（已评审） |

---

## 1. 结构选型与理由

代码组织有两种主流流派，必须先选一种，混用最难维护：

| 流派 | 形态 | 优点 | 缺点 | 是否采用 |
|---|---|---|---|---|
| package-by-layer（国内常见，RuoYi 式） | `controller/ service/ dao/ entity/` 装下全部业务 | 上手快、找"所有 Controller"方便 | 业务规模一大，单包爆炸；跨模块耦合无法约束；拆微服务时要全仓改 | ✗ |
| **package-by-feature（本项目采用）** | 每个业务特性一个包，内部自带 MVC 分层 | 边界天然清晰；可整体删除一个模块；契合"模块自包含"的架构主张 | 找"所有定时任务"这类横切物需按类型检索 | ✓ |

**Maven 拆几个模块？** 三种候选：

- **单模块**（interview-guide 现状）：最省事，但业务与"技术实现"之间的依赖只能靠自觉。
- **按业务拆 11 个 jar**：典型过度工程。改一个功能跨多个 pom，循环依赖会逼你往 common 里塞业务代码，最后 common 变成垃圾场。
- **✓ 按依赖层次拆 5 个模块**（本项目）：只在"编译期能强制约束"的地方切一刀，业务模块全部留在 `annona-server` 内以包形式并存。

核心取舍：**用 Maven 模块强制"业务不依赖技术实现"这一条（这才是会腐化的地方），其余边界用 ArchUnit 在测试期约束**（成本低、反馈快、不增加 pom 维护负担）。

---

## 2. 仓库顶层结构

```text
annona/
├── pom.xml                        # Aggregator + Parent：modules 声明、dependencyManagement、pluginManagement、enforcer
├── annona-common/                 # 通用能力层：与业务无关、与技术无关
├── annona-spi/                    # 扩展点契约层：纯接口 + 跨模块 DTO，零 Spring 依赖
├── annona-infrastructure/         # 技术实现层：SPI 的具体实现与外部系统适配
├── annona-server/                 # 应用层：全部业务模块 + Web + 启动类 + CLI（唯一可执行 jar）
├── annona-web/                    # 前端：Vite + React + TS，构建产物注入 server 的 static
├── docker/                        # 容器与编排：Dockerfile、compose、init.sql、entrypoint
├── deploy/                        # 部署脚本与运维资产：nginx、systemd、备份与恢复
├── scripts/                       # 工程脚本：评测、压测、seed、代码生成、发布
├── docs/                          # 文档：README 导航 + 三份主文档 + architecture/specs/plans/reports/development/tests/benchmarks/api（见 docs/README.md）
├── data/                          # 本地开发数据卷（.gitignore，仅样例数据入库）
├── .github/                       # 开源门面与自动化（详见 §13）
│   ├── workflows/                 #   ci.yml、e2e.yml、rag-eval.yml、release.yml、publish-spi.yml、stale.yml
│   ├── ISSUE_TEMPLATE/            #   bug_report / feature_request / skill-proposal（新增面试方向提案）
│   ├── PULL_REQUEST_TEMPLATE.md   #   含「是否影响决策输入或检索指标」勾选项
│   ├── dependabot.yml             #   （P1b-10 重新引入，见阶段总结 §5 D16；P0-P1a 期间不启用）
│   ├── CODEOWNERS                 #   模块目录 → 维护者，控制合并权限与评审路由
│   └── FUNDING.yml                #   赞助入口（开源可持续性）
├── .mvn/wrapper/                  # Maven Wrapper（统一 mvn 版本，clone 即可构建）
├── .githooks/                     # Git hooks：commit-msg 规范、pre-commit 密钥扫描
├── lombok.config                  # Lombok 全局配置（copyableAnnotations 等）
├── .editorconfig                  # 跨 IDE 缩进/编码一致性（已落地，Java/前端 2 空格、XML 4 空格）
├── .env.example                   # 环境变量模板（只放占位符，绝不放真实 Key）
├── .gitignore / .gitattributes    # 忽略规则与换行/LFS 约定
├── LICENSE                        # AGPL-3.0
├── README.md                      # 首屏：一句话定位 + 5 分钟跑通 + 截图 + 上游致谢
├── SECURITY.md                    # 密钥处理、数据导出删除、漏洞上报渠道
├── CONTRIBUTING.md                # 参与路径（指向规则，不重复内容）
└── CODE_OF_CONDUCT.md             # 行为准则（项目自有简短版，可整体换为 Contributor Covenant）
```

---

## 3. Maven 模块与依赖方向

```text
                     ┌───────────────────┐
                     │   annona-common   │  无外部依赖（除 slf4j/jackson/lombok）
                     └─────────┬─────────┘
                               ▲
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
┌───────┴────────┐    ┌────────┴────────┐    ┌────────┴─────────┐
│   annona-spi   │    │ annona-server   │    │ annona-infra     │
│ (只依赖 common) │    │ (依赖 common+spi)│    │ (依赖 common+spi) │
└────────────────┘    └────────┬────────┘    └──────────────────┘
                               │ runtime 依赖（编译期不可见）
                      ┌────────┴────────┐
                      │  annona-server  │  ← 启动类所在模块在运行期装配 infra 实现
                      └─────────────────┘
```

| 模块 | artifactId | 职责 | 允许依赖 | 禁止 |
|---|---|---|---|---|
| 通用 | `annona-common` | 统一响应、异常体系、枚举、常量、工具、注解与切面基类 | 第三方库 | 依赖任何业务概念、依赖 spi/infra/server |
| 契约 | `annona-spi` | 五个扩展点接口、跨模块契约 DTO、领域事件定义 | common | 引入 Spring / MyBatis / SDK（保证可被外部实现） |
| 技术 | `annona-infrastructure` | SPI 实现 + 外部系统适配（PG/pgvector、Redis、S3、LLM HTTP、加密、PDF、分词） | common, spi, 各类 SDK | 含业务规则、被 server 编译期直接引用实现类 |
| 应用 | `annona-server` | 11 个业务模块、REST/SSE/WS 接口、启动类、CLI、prompt 与 skill 资源 | common, spi（infra 仅 runtime） | 直接 `import` infrastructure 的具体实现类 |
| 前端 | `annona-web` | Vite 工程，`build` 产物拷贝到 server 的 `resources/static` | 无（独立） | 前端不感知 Java 结构，只依赖 `/api/*` 契约 |

**装配方向是关键设计**：`annona-server` 的业务代码只 `@Autowired` SPI 接口；具体实现由 `annona-infrastructure` 通过 `@ConditionalOnProperty` + `AutoConfiguration.imports` 在运行期注入。带来的三个具体收益：

1. 换检索后端（pgvector ↔ ES）、换身份来源（本地 ↔ 平台）、换模型供应商，业务代码零改动。
2. `annona-spi` 可单独发布，第三方能只依赖它写自己的 Retriever 或 IdentityProvider。
3. 单测里给 SPI 写个 `Fake`，业务模块可完全脱离中间件测试（不需要 Testcontainers 也能跑核心逻辑）。

**父 pom 约定**：版本全部集中在根 `dependencyManagement`（引入 `spring-boot-dependencies` / `spring-ai-bom` BOM），子模块 `<dependency>` 一律不写 version；`maven-enforcer-plugin` 强制 JDK 21、禁用 `Executors.newXxxThreadPool`（banned methods）、要求依赖收敛；`pluginManagement` 统一 compiler/surefire/flyway/spring-boot 插件版本。

---

## 4. 后端包结构（`annona-server/src/main/java`）

> **本节仅描述 `annona-server` 模块的内部包结构**；仓库顶层目录见 §2，其他 3 个 Maven 模块（`annona-common` / `annona-spi` / `annona-infrastructure`）的职责与允许依赖见 §3 表格。将代码归入哪个模块的判据在 §3，本节的树形仅回答"进了 `annona-server` 之后放哪儿"。

```text
io.annona
├── AnnonaApplication.java             # 启动类：仅 @SpringBootApplication + @EnableConfigurationProperties 汇总
│
├── config/                            # 【全局装配】所有 @Configuration 的家
│   ├── web/                           #   MVC 定制、CORS、静态资源、SSE 超时、OpenAPI/Swagger 分组
│   ├── async/                         #   四类线程池（通用/AI-IO/CPU/查询）+ Redis Stream 容器注册
│   ├── persistence/                   #   JPA/事务/审计字段/pgvector 类型注册、Flyway callback
│   ├── security/                      #   过滤器链、密码编码器、Key 解密上下文、CSP
│   ├── properties/                    #   @ConfigurationProperties 类（每域一个，禁止散落 @Value）
│   └── observability/                 #   Micrometer 指标、健康指示器、trace_id 过滤器
│
├── shared/                            # 【跨模块共享的内核物】不放业务规则，只放契约与读模型
│   ├── domain/                        #   领域事件定义（StudySessionClosed、InterviewEvaluated…）
│   ├── direction/                     #   方向字典只读访问（被 6 个模块消费，故独立于 identity/study）
│   ├── signal/                        #   LearningSignalReader 的门面与信号快照模型
│   ├── idempotent/                    #   幂等键生成与消费模板（交卷、回写、异步任务）
│   └── meta/                          #   非业务的运维探针端点（/api/meta/ping 等，P0-03 引入）
│
├── modules/                           # 【业务特性】每个包自包含，禁止跨模块 import 内部类
│   ├── identity/                      # 账号与身份
│   │   ├── controller/                #   注册/登录/会话/资料/头像 REST
│   │   ├── service/                   #   注册流程、会话管理、密码重置编排
│   │   ├── repository/                #   User、Session 持久化
│   │   ├── entity/                    #   AppUserEntity
│   │   ├── dto/                       #   请求/响应记录（record）
│   │   ├── provider/                  #   IdentityProvider 的本地/平台/免登录三种实现选择
│   │   └── config/                    #   模块内配置
│   │
│   ├── study/                         # 自习室与学习行为采集（A 模块）
│   │   ├── controller/                #   打卡、番茄钟、心跳、会话、统计查询接口
│   │   ├── service/                   #   会话聚合（心跳→Session→质量分级）、统计编排
│   │   ├── repository/ entity/ dto/   #   StudySession、StudyEvent、Checkin
│   │   ├── quality/                   #   ★ 数据质量判定：VERIFIED/PARTIAL/SELF_REPORTED 规则
│   │   ├── stats/                     #   热力图、趋势、方向分布等只读聚合（查询走读模型）
│   │   └── presence/                  #   匿名共学在线状态（Redis ZSET + TTL，只读接口）
│   │
│   ├── plan/                          # 计划、任务与待办（B 模块）
│   │   ├── controller/ service/ repository/ entity/ dto/
│   │   ├── todo/                      #   今日待办 CRUD + 供 Agent 调用的工具方法
│   │   ├── splitter/                  #   文档工作室：Markdown 计划 → 任务拆分
│   │   └── template/                  #   计划/文档模板库
│   │
│   ├── knowledge/                     # 知识库与文档 ETL（D 模块，不含检索）
│   │   ├── controller/ service/ repository/ entity/ dto/
│   │   ├── ingest/                    #   上传、格式解析调度、内容 hash 幂等
│   │   ├── parse/                     #   Tika 适配、结构识别（标题/段落边界）
│   │   ├── chunk/                     #   ★ 分块算法（重叠滑窗、死循环兜底）——纯逻辑，重点单测
│   │   ├── embed/                     #   向量化批处理、进度状态机、失败重试
│   │   ├── ops/                       #   重新向量化、下载、分类、统计
│   │   └── listener/                  #   Redis Stream 消费者（向量化任务）
│   │
│   ├── retrieval/                     # 检索能力（D 模块的读侧，与 knowledge 写侧分离）
│   │   ├── controller/ dto/           #   检索测试接口（query → 命中块 + 分数）
│   │   ├── hybrid/                    #   ★ 语义 + 关键词双通道、RRF 融合、余弦重排
│   │   ├── keyword/                   #   应用层中文分词适配、tsv 写入、pg_trgm 兜底
│   │   ├── rewrite/                   #   查询改写、TopK 自适应、相似度阈值
│   │   ├── compress/                  #   上下文压缩与去冗余
│   │   ├── fallback/                  #   联网搜索降级、来源标注
│   │   └── provider/                  #   PgVectorRetriever / EsRetriever（实现 annona-spi 的 Retriever）
│   │
│   ├── qa/                            # RAG 问答会话（D 模块的交互层）
│   │   ├── controller/                #   SSE 流式问答、会话管理、置顶、多库关联
│   │   ├── service/                   #   引用组装、消息持久化、流式编排
│   │   └── entity/ repository/ dto/   #   Conversation、ConversationMessage
│   │
│   ├── questionbank/                  # 题库（E 模块）
│   │   ├── controller/ service/ repository/ entity/ dto/
│   │   ├── generate/                  #   ★ 从文档出题：主问题/参考答案/关键点/评分标准/追问
│   │   ├── dedup/                     #   历史题目去重（向量相似 + 题干关键词双判）
│   │   ├── calibration/               #   难度标定：按实测答对率回写 hit_rate
│   │   └── capacity/                  #   面试容量校验（追问数是硬约束）
│   │
│   ├── interview/                     # 面试引擎（C 模块，文字）
│   │   ├── controller/ service/ repository/ entity/ dto/
│   │   ├── skill/                     #   ★ SKILL.md 加载、解析、注册表（内置 + 用户 + 知识库派生）
│   │   ├── orchestrator/              #   组卷：方向配额 → 难度分布 → 阶段编排 → 主问题/追问
│   │   ├── stage/                     #   阶段时长联动（自我介绍/考察/深挖/反问）
│   │   ├── followup/                  #   追问生成与深度控制
│   │   ├── state/                     #   状态机 + 中断续面（Redis 热 / DB 冷）
│   │   ├── jd/                        #   JD 解析 → 临时方向
│   │   └── report/                    #   雷达图、加权总分、逐题评价聚合
│   │
│   ├── evaluation/                    # 评估引擎（C 与 F 共用，独立成模块）
│   │   ├── service/ dto/              #   评估任务编排、结果落库
│   │   ├── batch/                     #   分批评估与二次汇总
│   │   ├── structured/                #   ★ 结构化输出重试（统一入口，禁止业务侧复制重试）
│   │   ├── fallback/                  #   降级兜底、fallback_used 标记
│   │   └── comparability/             #   ★ 可比性：模型/prompt 版本留痕、难度加权、区间判断
│   │
│   ├── planner/                       # 训练决策层（G 模块，项目灵魂，不挂在 interview 下）
│   │   ├── controller/                #   决策留痕查询、反驳提交、面板数据
│   │   ├── service/ entity/ repository/ dto/   # DecisionTrace
│   │   ├── signal/                    #   输入装配：时长(带质量) + 完成率 + 得分 + 间隔
│   │   ├── mastery/                   #   ★ 掌握度与遗忘曲线计算（纯函数，重点单测）
│   │   ├── rule/                      #   规则链：每条规则一个实现（DecisionRule SPI）
│   │   ├── guard/                     #   ★ 保护规则：样本量、质量、占比上限、版本切换基线
│   │   ├── explain/                   #   可解释文案生成（把 trace 翻成人话）
│   │   └── advisor/                   #   InterviewPlanAdvisor：对面试侧暴露的唯一入口
│   │
│   ├── voice/                         # 语音面试（F 模块）
│   │   ├── handler/                   #   WebSocket 握手与消息路由
│   │   ├── session/                   #   会话生命周期、暂停/恢复、多轮上下文
│   │   ├── asr/                       #   流式 ASR + VAD 断句 + 中间结果字幕
│   │   ├── tts/                       #   ★ 句子级并发 TTS（首句优先、边合成边推）
│   │   ├── audio/                     #   PCM 缓冲、回声防护、手动提交模式
│   │   ├── metrics/                   #   端到端/分段延迟埋点
│   │   └── config/ dto/
│   │
│   ├── schedule/                      # 面试日程（I 模块）
│   │   ├── controller/ service/ repository/ entity/ dto/
│   │   ├── parse/                     #   邀请解析：规则引擎 + AI 双引擎（飞书/腾讯会议/Zoom）
│   │   ├── calendar/                  #   日/周/月视图查询、拖拽改期
│   │   ├── lifecycle/                 #   状态流转、定时过期
│   │   └── cram/                      #   ★ 面试前突击入口（编排 planner + questionbank）
│   │
│   ├── agent/                         # AI 智能体（H 模块）
│   │   ├── controller/ service/ dto/
│   │   ├── run/                       #   run/step/toolcall/decision/approval 留痕
│   │   ├── tool/                      #   工具注册表（todo、plan、knowledge、report）
│   │   ├── memory/                    #   长期记忆提取与注入（P5 才启用）
│   │   ├── workflow/                  #   学习计划工作流、周报、每日总结
│   │   └── scheduler/                 #   定时任务调度 + 模型档位降级
│   │
│   ├── usage/                         # 用量与配额（J 模块，上线必需）
│   │   ├── controller/ service/ entity/ repository/
│   │   ├── metering/                  #   ★ token 记账（场景/模型/成本），异步落库不进事务
│   │   ├── quota/                     #   每日配额与超额熔断
│   │   └── key/                       #   模型 Key 生命周期：加密、掩码、KEK 轮换（见 §12.1 产品设计）
│   │
│   ├── resume/                        # 简历分析（C 的前置）
│   │   ├── controller/ service/ repository/ entity/ dto/
│   │   ├── parse/                     #   简历解析、重复检测
│   │   ├── analysis/                  #   AI 分析报告
│   │   └── listener/                  #   Redis Stream 异步消费与失败重试
│   │
│   └── notify/                        # 通知中心（横切，但含业务规则故留在 modules）
│       ├── controller/ service/ entity/ repository/
│       └── dedup/                     #   每日去重、Markdown 渲染
│
├── cli/                               # 同一 jar 内的运维命令面（picocli，profile=cli 时激活）
│   ├── AnnonaCli.java                 #   export / reindex / eval / seed / reset-password / bench
│   └── command/                       #   每命令一个类，只编排不写业务规则
│
└── bootstrap/                         # 启动期一次性动作
    ├── DataSeeder.java                #   `annona demo seed --weeks 6` 合成历史数据
    └── StartupValidator.java          #   ★ 缺 KEK / 缺模型 / 表结构漂移时拒绝启动
```

`★` 标记 = 该包承载本项目独有能力，需要重点单测与文档。

---

## 5. 单个业务模块的内部标准分层

所有 `modules/*` 必须能对上这张模板（缺项允许，多项禁止），这样"看一个模块就看懂全部模块"：

```text
io.annona.modules.<name>/
├── controller/      # @RestController / @Controller：只做路由、参数校验、委托；返回 Result<T>
├── service/         # 业务编排 + @Transactional（只在这一层）；模块对内唯一的编排入口
├── repository/      # Spring Data JPA 接口；自定义查询用 @Query 或方法名
├── entity/          # XxxEntity，JPA 映射；不得出现在 controller 出入参
├── dto/             # record：XxxRequest / XxxResponse / XxxDTO
├── mapper/          # MapStruct：Entity ↔ DTO
├── <capability>/    # 可选：模块内需要独立演进的能力（见上表 ★ 项）
├── listener/        # 可选：Redis Stream 消费者 / 领域事件订阅
├── config/          # 可选：模块专属配置与线程池
└── package-info.java # 声明模块职责与允许的外部依赖（团队约定入口）
```

**跨模块通信只允许两种方式**：

1. 只读查询 → 通过对方 `service` 暴露的 `XxxQueryService` 接口（或 `shared/` 下的读模型）。
2. 状态变更 → 发领域事件（`shared/domain`），由对方 `listener` 消费；不直接调对方写服务。

例外只有一个：`planner/advisor` 可被 `interview/orchestrator` 同步调用（出题是决策的即时消费方），这是设计文档里固定的强关联。

---

## 6. 后端资源目录（`annona-server/src/main/resources`）

```text
src/main/resources/
├── application.yml                  # 主配置：端口、profile 组、日志级别、公共默认值
├── application-dev.yml              # 本地：H2/本地 PG、fallback KEK 显式声明、DDL validate
├── application-docker.yml           # compose 环境：服务名主机、卷路径
├── application-prod.yml             # 生产：无 fallback、Flyway 只读校验、日志 JSON
├── logback-spring.xml               # 日志：结构化输出 + trace_id + Key 脱敏 converter
├── db/
│   ├── migration/                   # ★ Flyway：V1__baseline.sql、V2__direction_dict.sql…
│   │                                #   命名 V<版本>__<snake_case 描述>.sql；U__ 回滚脚本按需
│   └── seed/                        # 演示数据 SQL（仅 dev/docker profile 生效）
├── prompts/                         # ★ StringTemplate 模板：interview-question.st、evaluation-summary.st…
├── skills/                          # ★ 内置 SKILL.md：skills/java-concurrency/SKILL.md 等
├── fonts/                           # PDF 中文字体（NotoSansCJK 子集）
├── templates/                       # PDF/邮件模板（HTML→PDF 用）
├── static/                          # 前端构建产物（由 annona-web 拷贝，本仓不手写）
└── META-INF/spring/                 # AutoConfiguration.imports（infra 侧装配入口）
```

测试资源：

```text
src/test/resources/
├── application-test.yml             # H2 + Fake SPI + 禁用真实模型调用
├── fixtures/                        # 样例 PDF/DOCX/MD、简历、面试邀请原文、逐题答案
├── golden/                          # 期望输出快照（分块结果、决策 trace、报告结构）
└── eval/                            # RAG 评测 query 集与标注（recall/mrr 基线）
```

---

## 7. 前端包结构（`annona-web/src`）

```text
annona-web/
├── index.html
├── vite.config.ts                   # 代理 /api、构建产物输出到 server/static、three.js 分包
├── playwright.config.ts             # e2e
├── public/
│   ├── models/                      #   3D 岛屿 glb/gltf 资源
│   ├── audio/                       #   环境音（雨/雪/林）
│   └── images/
└── src/
    ├── main.tsx / App.tsx           # 入口与路由树
    ├── api/                         # ★ Axios 单实例 + 按域拆分的请求封装（identity/study/knowledge/…）
    ├── types/                       # 与后端 DTO 对齐的类型定义（禁止页面内重复声明）
    ├── constants/                   # routes.ts、direction、枚举字典
    ├── router/                      # 路由表、懒加载边界、鉴权守卫
    ├── stores/                      # 全局状态（auth、presence、ui 主题）；服务端数据不进 store
    ├── hooks/                       # usePomodoro、useHeartbeat、useSSEStream、useVoiceSession…
    ├── layouts/                     # AppLayout（侧边栏四平级入口）、AuthLayout、FocusLayout（沉浸）
    ├── pages/                       # 按功能域一目录一页面：dashboard/study/plans/knowledge/qa/
    │   │                            #   interview/voice/schedule/agent/profile/settings
    ├── components/
    │   ├── ui/                      #   基础件（button/dialog/toast/tabs…）
    │   ├── island/                  #   ★ three.js 小岛：场景、生长模型、降级 2D
    │   ├── heatmap/                 #   打卡热力图与趋势
    │   ├── md-editor/               #   文档工作室（Markdown + 净化渲染）
    │   ├── explain-panel/           #   ★ 决策可解释面板（reasons 渲染、反驳按钮）
    │   └── report/                  #   雷达图、逐题评价、PDF 报告预览
    ├── styles/                      # Tailwind 主题、★ 场景主题 token 包（rainforest/snow/warmcloud）
    ├── utils/                       # 时间/格式化/下载/颜色
    └── i18n/?                       # 暂不引入（v1 仅中文），目录预留位则视为过度设计
```

前端只依赖 `/api/*` 与 `/ws/voice` 契约，不感知 Java 结构；类型定义以 OpenAPI 生成为准（`scripts/gen-api.ts`）。

---

## 8. 测试目录结构

```text
annona-server/src/test/java/io/annona/
├── modules/<name>/                  # 与被测包一一对称（镜像结构是硬性要求）
│   ├── service/                     #   单测：Mockito + AssertJ，@DisplayName 中文意图
│   └── <capability>/               #   ★ 纯逻辑单测：chunk / mastery / guard / structured
├── infrastructure/                  #   分词、向量序列化、加密、PDF 等技术单测
├── integration/                     #   @SpringBootTest + H2/Testcontainers：接口冒烟、事务边界
├── arch/                            #   ★ ArchUnit：结构守门规则（见 §10）
└── support/                         #   测试夹具构建器、Fake SPI 实现、断言辅助
```

分层验证门槛：改公共能力必须跑 `mvn test`；改 planner / evaluation / chunk 必须附带 golden 快照测试；RAG 相关改动跑 `scripts/rag-eval`（CI 可选阶段，不阻断）。

---

## 9. 脚本、部署与文档目录

```text
scripts/
├── seed/                            # 合成学习数据（决策层冷启动演示）
├── rag-eval/                        # Recall@K / MRR 评测与基线对比
├── bench/                           # 并发压测与延迟分位数（语音端到端、接口 P95）
├── gen/                             # OpenAPI → 前端类型生成
└── release/                         # 版本发布、changelog、镜像构建

docker/
├── Dockerfile                       # 多阶段：前端 build → maven package → JRE 运行时
├── docker-compose.yml               # pgvector + redis + minio + server + web
├── docker-compose.dev.yml           # 只起中间件，本地跑 app
└── postgres/init.sql                # CREATE EXTENSION vector

deploy/
├── nginx/annona.conf                # 反代、WS 升级、SSE 不缓冲、静态缓存
├── systemd/annona.service
└── backup/                          # pg_dump + S3 生命周期脚本

docs/                              # 结构见 docs/README.md（导航 + 各目录准入条件）
├── README.md                      # 阅读地图与写作约定
├── annona-项目设计文档.md / annona-项目结构.md / annona-开发计划.md
├── architecture/                  # INDEX.md + overview.md + 每模块一篇（实现期写）
├── specs/                         # 日期命名 ADR：YYYY-MM-DD-<topic>-adr.md
├── reports/                       # P<n>-<阶段名>-阶段总结.md（每阶段收尾强制产出）
├── plans/ development/ tests/ benchmarks/ api/
```

---

## 10. 结构守门规则（ArchUnit 强制，违反即 CI 失败）

```text
io.annona.modules..                 禁止依赖 io.annona.infrastructure..（编译期不可见实现类）
io.annona.modules.<a>..             禁止依赖 io.annona.modules.<b>..（除 planner→interview 白名单）
io.annona..controller..             禁止出现在 service/repository 之上以外的反向依赖
io.annona..entity..                 禁止作为 controller 方法出入参类型
io.annona.spi..                     禁止依赖 Spring / Jakarta Persistence / 任何 SDK
io.annona.modules.planner..         禁止依赖 interview/voice/schedule（只能被它们调用或读 shared 信号）
全局                                  禁止 java.util.concurrent.Executors 的 newCached/newFixed 等方法
```

新增例外只能通过在 `docs/specs/` 提交一条 ADR 后修改白名单，不允许在代码里 `// noop` 绕过。

---

## 11. 命名规范速查

| 对象 | 规则 | 示例 |
|---|---|---|
| Maven 模块 | `annona-<kebab>` | `annona-infrastructure` |
| Java 包 | 全小写、无下划线、`io.annona.modules.<域>` | `io.annona.modules.questionbank` |
| 启动类 | `AnnonaApplication` | — |
| 实体 / DTO | 后缀 `Entity` / `Request` / `Response` / `DTO` | `InterviewSessionEntity`、`StartInterviewRequest` |
| 仓储 / 映射 | `XxxRepository` / `XxxMapper` | `DecisionTraceRepository` |
| 配置属性 | `annona.<域>.*` + `XxxProperties` | `annona.planner.mastery.*` |
| Flyway | `V<n>__<snake_case>.sql` | `V7__add_decision_trace.sql` |
| Prompt | `<用途>-<动作>.st` | `interview-question.st` |
| SKILL | `skills/<方向key>/SKILL.md` | `skills/java-concurrency/SKILL.md` |
| 表 / 列 | snake_case，业务表必含 `user_id` | `study_session.direction_key` |
| 测试类 | `被测类 + Test`（单测）/ `+IT`（集成） | `MasteryCalculatorTest` |
| Git 分支/提交 | `feat\|fix\|docs\|refactor\|test\|chore: <描述>`（commit-msg hook 校验，**P0-11 落地前靠自觉**） | — |

代码风格：2 空格缩进、无通配符 import、构造器注入 + `@RequiredArgsConstructor`、SLF4J 占位符且异常作为最后一个参数。

---

## 12. 当前仓库 → 目标结构的 P0 重组清单

目标结构（§2–§11 描述的完整形态）不是一次到位的，P0 分 5 批交付。下表登记当前进度：

| # | 目标 | 状态 | 落地批次 |
|---|---|---|---|
| 1 | 根 `pom.xml` 改为 `packaging=pom` + `dependencyManagement`/`pluginManagement` + enforcer（JDK 21） | ✅ 已完成（P0-01） | 先于 B1 |
| 2 | 新建 `annona-common`、`annona-spi`、`annona-infrastructure`、`annona-server` 四模块，把现有 `src/` 迁入 `annona-server` | ✅ 已完成（P0-02，PR #2 merge `37c1295`） | B1 |
| 3 | `annona-web` 初始化（Vite + React + TS + Tailwind 4），配置构建产物拷贝进 `annona-server/resources/static` | ✅ 已完成（P0-09，PR #4 merge `47c2979`） | B3 |
| 4 | 落地 `config/{web,async,persistence,security,properties,observability}` 六包骨架 + `shared/{domain,direction,signal,idempotent,meta}` 子包；`modules.<name>` 顶层包与 `shared` 子包必须有 `package-info.java` | ✅ 已完成（B1 P0-07 落包骨架 + B2 P0-05 落六包与四线程池，PR #3 merge `f0a60d9`） | B1 + B2 |
| 5 | Flyway 基线 `V1__baseline.sql`（含 `direction` 字典表）、`db/seed/`、`prompts/`、`skills/` 目录占位 | ✅ 已完成（P0-06，`V1__baseline.sql` + `docker/postgres/init.sql` 两文件，PR #3 merge `f0a60d9`）；`db/seed` 与 `prompts/` `skills/` 目录占位待 P1a 填 | B2 |
| 6 | `annona-server/src/test/java/io/annona/arch/` 落地 §10 的 ArchUnit 七条规则（先失败后放行的红名单机制） | ✅ 已完成（P0-07，B1） | B1 |
| 7 | `docker/` 双 compose、`deploy/nginx/`、`.github/workflows/ci.yml`（build/test/lint/archunit + gitleaks 密钥扫描） | ✅ 已完成（P0-10 `docker/Dockerfile` 三阶段 + `docker-compose.yml`/`compose.dev.yml`，P0-11 hooks，P0-12 `ci.yml` 5 blocking jobs + 5 个辅助 workflow + `CODEOWNERS`/ISSUE/PR 模板/FUNDING，P0-13 `Makefile`）。~~`dependabot.yml`~~ 已从 P0 产出中移除（见 D16） | B4 + B5 |
| 8 | 仓库门面：`README.md`、`LICENSE`（AGPL-3.0 FSF 原文）、`AGENTS.md`、`.env.example`、`SECURITY.md`、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`、`.editorconfig` 已落地；`.github/` 全套见 §13（P0-12）；决策记录已在 `docs/specs/` 落地 8 条 ADR | ✅ 门面 + `.github/` 全套完成（P0-14，PR #6 本批；`CODEOWNERS` 强审 4 区：`planner/` `direction/` `annona-spi/` `docs/specs/` + `.github/` `.githooks/`） | B5 |

**验收**：`mvn -q verify` 全绿、ArchUnit 七条规则生效（本机到此为止）；`docker compose up` 后首页 200 与 `/api/meta/ping` 、模型连通性测试由 **CI 验证并留存日志**（本机无 Docker，见 `specs/2026-09-25-dockerless-local-dev-adr.md`）。

---

## 13. 开源协作与外部贡献结构

单人项目最大的风险不是写不完，是**除了作者没人能提 PR**。因此贡献者入口与代码结构同等重要，且必须在 P0 就搭好（后补的成本是「这段时间一个外部贡献都没有」）。

### 13.1 `.github/` 自动化清单

| 文件 | 职责 | 触发 |
|---|---|---|
| `workflows/ci.yml` | 五个 job：编译 + 单测 + ArchUnit + 前端 typecheck/lint/build + gitleaks 密钥扫描；**集测用 `services:`（`pgvector/pgvector:pg16` + `redis:7-alpine`），compose 冒烟单独一个 job** | PR 与主干 push |
| `workflows/e2e.yml` | Playwright 跑关键路径（注册→上传→面试→报告） | PR 标签 `needs-e2e` 或每日定时 |
| `workflows/rag-eval.yml` | 跑 `scripts/rag-eval`，将 Recall@K/MRR 差值写成 PR 评论 | 改动 `retrieval/` 或 `knowledge/chunk/` 时 |
| `workflows/release.yml` | 打 tag → 构建镜像 → GitHub Release + changelog | `v*` tag |
| `workflows/publish-spi.yml` | 将 `annona-spi` 发布到 Maven Central（GPG 签名 + sources/javadoc） | spi 目录变更的 tag |
| `workflows/stale.yml` | 30 天无回应自动关闭 issue/PR | 定时 |
| `dependabot.yml` | **P0-P1a 不启用**（骨架阶段无 CVE 暴露面，自动 PR 噪声大于价值）。P1b-10 首次接 BYOK 真实 Key 前写 ADR 重新引入，分组与 ignore 策略届时定。见阶段总结 §5 D16 | 目标 P1b-10+ |

本表的**完整执行矩阵（哪个验证用 `services:`、哪个才用 compose、用哪个镜像、paths 怎么过滤）以 `docs/specs/2026-09-25-dockerless-local-dev-adr.md` 为准**。要点：集测不走 compose（runner 托管的 services 更快），真正需要 compose 的只有“验证交付物”那一个 job。

### 13.2 降低参与门槛的四个具体物品

1. **`Makefile`（或 justfile）入口**，新人不需要记 Maven/npm 命令：`make up / make dev / make test / make eval / make logs / make reset`。
2. **`make quickstart` 一条命令**：起中间件 → 迁移 → seed 演示数据（含 6 周合成学习记录）→ 打出登录地址与演示账号。README 首屏就写这一条，它是 star 转 self-host 的转化率关键。
3. **`good-first-issue` 标签体系**：预置三类不碰核心逻辑的首 PR 入口 —— 新增/修订 `skills/<direction>/SKILL.md`、前端主题 token 包、文档与翻译。目录固定，贡献者不需要理解 Java。**投放时机：P2 有可演示物之后**——在那之前挂标签等于邀请别人改一个还跑不起来的项目。另：本项目自身施工任务**不建 issue**，进度真相源是开发计划任务表，**每阶段一个汇总 issue** 对外展示进度。
4. **`docs/development/NN-*.md` 专题教程**：同一个问题被问两次就写一期，首三篇建议：`01-本地跑通与目录导航`、`02-写一个自己的SKILL.md`、`03-实现一个Retriever`。

### 13.3 四类贡献各自的落点

| 贡献类型 | 改哪里 | 必须同时交 | 是否需 ADR |
|---|---|---|---|
| 新增面试方向 | 只加 `annona-server/src/main/resources/skills/<key>/SKILL.md` | 方向归属（父级 key）+ 难度分布依据 + 至少 3 道样题 | 否 |
| 新增模型 Provider | `infrastructure/llm/` 新增实现 + 注册表配置 | 连通性测试（可跳过，打 `@External` 标签） | 否 |
| 新增检索后端 / 身份源 | **建议在自己仓库里** 依赖 `io.annona:annona-spi` 实现 | 实现说明 + 与默认后端指标对比 | 否（不进主仓） |
| 修业务缺陷 | 对应 `modules/<name>/` | 先交一个能复现的失败测试 | 否 |
| 改决策算法 / 数据模型 | `modules/planner/*`、`shared/direction`、Flyway | **必须先在 issue 提案并补一条 ADR**，并说明对可解释面板文案的影响 | 是 |

关键一点：`annona-spi` 是**唯一**对外发布的 artifact（因此需要 Central 坐标、GPG 签名、无 Spring 依赖）。第三方扩展本身不开主仓 PR，形成“核心稳、生态活”的结构；否则每多一个 Provider 实现主仓依赖树就胖一次。

### 13.4 评审门禁（机器 > 约定）

| 阶段 | 机制 | 失败后果 |
|---|---|---|
| commit | `.githooks/commit-msg`：`feat\|fix\|docs\|refactor\|test\|chore: <描述>` | 拒绝提交 |
| commit | `.githooks/pre-commit`：gitleaks | 拒绝提交（防 Key 入仓） |
| PR | ArchUnit 7 条结构规则 | CI 红，不可合并 |
| PR | 测试覆盖率阈值（`planner`/`evaluation`/`chunk` 三个关键包 85%，其余 60%） | CI 红 |
| PR | 检索指标退化（Recall@K 降幅 > 2%） | 黄标提醒，需 PR 描述里给出理由 |
| PR | `@Tag("docker")` 集成测试与 compose 冒烟 | **仅 CI 执行**（本机无 Docker），未绿不可合并 |
| 合并 | DCO 签名（`Signed-off-by`），不用 CLA | 机器人拦截未签名提交 |

### 13.5 治理

- **决策升级路径**：issue 讨论 → 14 天无共识或影响数据模型 → 作者写 ADR 定收。单人项目不存在长期悬而不决，但必须写明“谁拍板”。
- `CODEOWNERS` 按模块目录划分，`modules/planner/` 与 `shared/direction/` 作者强审（这两处是产品灵魂，误接受外部重构代价最高）。
- 许可：代码 AGPL-3.0；`skills/` 下的内置 SKILL.md 单独用 CC-BY-4.0（便于站外引用与社区改写，不带动主仓许可）。
- `SECURITY.md` 声明漏洞上报渠道与响应时限，并明确声明**项目不内置任何模型 API Key**（见 `specs/2026-09-25-model-api-key-adr.md`）。
