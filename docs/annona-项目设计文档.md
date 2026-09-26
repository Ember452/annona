# 年轮 · annona —— 产品设计文档

> **你练过的每一分钟，都会长成下一道题的形状。**

| 项 | 内容 |
|---|---|
| 项目名 | 年轮（英文仓库名 `annona`，拉丁语 *annona* =「年度产出」，词源 `annus`=年） |
| 形态 | 单仓全栈 Web 应用，开源自部署（AGPL-3.0）+ 官方托管（BYOK 或平台代持 Key） |
| 一句话 | 把「自习室的行为数据」变成「AI 面试官的出题依据」的训练闭环平台 |
| 定位 | 不是「自习工具 + 面试工具 + 知识库工具」的合集，而是用一条数据主线把它们缝成一个能自我修正的系统 |
| 后端 | Java 21 · Spring Boot 4.1.x · Spring AI 2.0.x · Maven · Flyway |
| 前端 | React 19 · TypeScript · Vite · TailwindCSS 4 · three.js/react-three-fiber |
| 存储 | PostgreSQL 16 + pgvector · Redis 7 · S3 兼容对象存储（实现选型 PGSTY Silo，见 [specs/2026-09-26-s3-storage-silo-adr.md](./specs/2026-09-26-s3-storage-silo-adr.md)；**不用 MySQL / MongoDB / 默认不部署 ES**，理由见 [specs/2026-09-25-storage-single-postgres-adr.md](./specs/2026-09-25-storage-single-postgres-adr.md)） |
| 文档状态 | v1.0 设计定稿（已评审） |
| 关联文档 | [README.md](./README.md)（文档总导航）、[annona-项目结构.md](./annona-项目结构.md)（Maven 模块与包结构）、[architecture/overview.md](./architecture/overview.md)（分层与生命周期）、[specs/](./specs/)（ADR 决策记录） |

---

## 1. 为什么需要它

备考与面试训练工具有两类，各自都有天花板：

- **自习/番茄类工具**：精确记录了你坐在桌前的 4 小时，但不知道这小时有没有效，也不知道下一步该练什么。数据只用于「感动自己」，不参与任何决策。
- **AI 面试类工具**：能出题、能打分，但它对「你今天状态如何、哪块练得多、哪块两周没碰」一无所知。出题是随机的，练 100 场和练 1 场拿到的是同一套题。

**年轮做的是把第一类的输出接到第二类的输入上。** 你的专注时长、任务完成率、历史面试得分、上次练习距今的天数，共同决定下一次面试问哪个方向、出多难的题、追问几层、要不要掺入复习题。而每一次决策的依据都落库、都可查看——系统必须能回答「你凭什么这么考我」。

这个闭环是项目存在的唯一理由。抽掉它，剩下的就是一堆别人做得更好的功能页签。

---

## 2. 目标用户与场景

| 用户 | 场景 | 关键诉求 |
|---|---|---|
| 技术求职者 | 晚上刷 2h 八股 + 项目复盘，周末模拟面 | 别让已经会的再问一遍，把弱项逼出来 |
| 考研（408/专业课） | 长周期复习，复试口语+专业问答 | 忘记的内容要自动回锅，复习要有依据 |
| 法考 / CPA / 教资 | 巨量知识点、遗忘极快 | 上传讲义就能生成专项面试，不依赖内置题库 |
| 公务员 / 事业编 | 结构化面试、无领导小组、时政素材 | 按题型练，要评分标准和参考答话 |
| 自部署开发者 / 学习者 | `docker compose up`，填自己的 Key | clone 下来 5 分钟能跑，数据是自己的 |

**不做的用户**：企业招聘方、培训机构、需要团队协作的 B 端（见 §17 Non-goals）。

---

## 3. 三条设计主张

**主张一 · 一条数据主线，入口全部平级。**
侧边栏上自习室 / 模拟面试 / 知识问答 / 计划日程是四个平级入口，谁也不是 VIP 功能。但实现上它们共用同一内核（身份、模型网关、知识库、学习信号、方向字典）。并列的是界面，共用的是地基。

**主张二 · 可解释优先于准确。**
任何一条系统自动做出的决定（今天问你这题、难度升到 4、给你 3 层追问），都必须能在 UI 上点开看到「因为：方向 X 距今 12 天 / 上次得分 58 / 该方向专注占比 6%」。做不到可解释的决策就不许上线。理由：LLM 评分本身有噪声，用户无法验证；唯一能建立信任的是把推理链摊开。

**主张三 · 内容不自产，方向由用户喂。**
我们承诺技术方向的内置 `SKILL.md`（可开箱即面），承诺**方法论**（结构化/半结构化/STAR/追问规则）内置；不承诺自己编写的法考、CPA、考研专业课考点——那些由用户上传讲义驱动，系统从知识库里出题。这样「不限程序员」是用户能自己实现的性质，而不是我们要背的 KPI。

---

## 4. 功能全景

标记：`G`=参考 interview-guide　`M`=参考 MockPilot　`S`=参考 summer-checkin　`新`=本项目独有。均为**能力与设计借鉴，不搬运代码**。

### A. 自习室与学习行为采集

| 功能 | 来源 |
|---|---|
| 3D 学习小岛（连续天数 + 累计时长 + 方向覆盖度驱动生长形态） | S |
| 每日打卡 + 心情/自评能量值 | S |
| 番茄钟专注（自定义时长，结束自动写入学习会话并关联方向） | S |
| 长时段专注模式（无强制休息，配休息提醒） | 新 |
| 沉浸模式（全屏无干扰，仅时间 + 鼓励语 + 环境音） | S |
| 年度打卡热力图 / 专注趋势 / 日均时长 / 方向分布饼图 | S |
| 场景主题（雨林 / 雪日 / 暖云）+ 环境音（离开站点自动静音），**以 CSS token 主题包实现，不做逐页换肤** | S改造 |
| 匿名共学状态（在线人数、N 人专注中；Redis TTL 轮询，无房间、无 UGC） | S改造 |
| 个人主页（累计数据、方向雷达、最近报告） | S |

### B. 计划、任务与待办

| 功能 | 来源 |
|---|---|
| 计划—任务—打卡三方联动，任务制进度 | S |
| 今日待办（AI 可增删改查） | S |
| 文档工作室：Markdown 写计划 → AI 拆分为任务 | S |
| 计划/文档模板库 + 新手引导游标 | S |
| **决策反哺任务**：训练决策层可自动追加「复习型任务」到明日待办，并写明理由 | 新 |

### C. 多领域 AI 模拟面试

| 功能 | 来源 |
|---|---|
| Skill 驱动出题：一个方向 = 一份 `SKILL.md`（考察范围 / 难度分布 / 参考知识库 / 评分侧重） | G |
| 内置技术方向 10+（Java 后端、前端、Python、算法、系统设计、测开、AI Agent、阿里/字节/腾讯专项） | G |
| **内置方法论方向**：结构化面试、半结构化、行为面 STAR、项目深挖、英文自我介绍（标 experimental） | 新 |
| **知识库驱动方向**：任何用户上传资料 → 生成方向 → 出题（考研/法考/CPA/教资/行测申论/产品/运营） | G改造 |
| 面试阶段时长联动（总时长滑块 → 各阶段按时比自动分配） | G |
| 可配置多轮智能追问（追问深度可被决策层覆盖） | G |
| 历史题目去重（向量相似 + 题干关键词双判） | G+M |
| JD 解析 → 岗位定制面试 | G |
| 简历上传解析 + AI 分析报告 + 简历库 + 重复检测 + 失败重试 | G |
| 面试状态机 INIT→IN_PROGRESS→FINISHED→EVALUATED + 中断续面（Redis 热 / DB 冷） | G+M |
| 统一评估引擎：分批评估 + 结构化输出重试 + 二次汇总 + 降级兜底，文字与语音共用 | G |
| 雷达图 + 加权总分 + 逐题评价 + 优势/改进建议 | G |
| 面试中心（继续/重新）、记录筛选、表现趋势 | G |
| PDF 评估报告与简历报告异步导出（内置中文字体） | G |
| **错题沉淀**：答错的题 + 参考答案 + 评分标准回写为用户知识文档，可被再次检索与出卷 | 新 |
| **同题多模型对照**：同一答案交给 2–3 个模型评分，展示分差（评分稳定性） | 新 |

### D. 知识库与 RAG 问答

| 功能 | 来源 |
|---|---|
| 多格式上传（PDF/DOCX/DOC/TXT/MD/HTML/图片 OCR 可选） | G |
| 异步 ETL：解析 → 智能分块（段落边界 + 句末检测 + 重叠滑窗 + 死循环兜底）→ Embedding → 向量化，进度可见 | G+M |
| 混合检索：pgvector 语义 + 关键词通道 → RRF 融合 → 余弦重排 | M（PG 重实现） |
| 检索后端可插拔（pgvector / ElasticSearch 一键切换，用于 A-B 对比） | 新 |
| 查询改写、相似度阈值、TopK 自适应、上下文压缩 | G+M |
| SSE 打字机流式问答、会话管理/置顶、多库关联、虚拟列表 | G |
| 源引用追溯（点击跳回原文段落并高亮） | M |
| 联网搜索降级（本地检索不足时兜底，来源标注） | M |
| Markdown 渲染 + HTML 净化白名单防注入 | S |
| 知识库运维：分类、下载、重新向量化、检索测试（query 直接看命中块）、统计 | G+新 |
| **Embedding 幂等**：按内容 hash 跳过重复向量化 | S |

### E. 知识库 → 题库 → 专项面试

| 功能 | 来源 |
|---|---|
| 从已向量化文档生成主问题 + 参考答案 + 关键点 + **评分标准** + 追问，按方向与难度组织 | G |
| 异步出题、不足保留草稿、实际/目标追问数显式提示 | G |
| 题库维护：搜索、筛选、分页、单条增删改、批量状态流转（草稿/启用/归档） | G |
| 严格面试容量校验（追问数为硬约束，不足禁用选项 + 后端兜底） | G |
| 知识库专项面试 + 复用统一评估引擎 | G |
| **题目难度落库与标定**：出题时给 `difficulty`，评估后按实测答对率回写校准 | 新 |

### F. 语音面试

| 功能 | 来源 |
|---|---|
| WebSocket 实时双向语音对话 | G |
| 流式 ASR + 服务端 VAD 自动断句 + 实时字幕（含中间结果） | G |
| LLM 流式 + 句子级并发 TTS（边生成边合成边播） | G |
| 回声防护 + 手动提交模式 | G |
| 暂停/恢复、超时自动暂停、多轮上下文记忆 | G |
| 开场白可配置、Micrometer 埋点（ASR/TTS 延迟、会话时长） | G |
| 麦克风采集 + AudioWorklet PCM 处理 | G |
| **端到端延迟预算表 + P50/P95 实测基线**（停止说话 → 首包音频），作为验收门槛而非宣传语 | 新 |
| AI 多面试官压力面（单会话内扮演 3 个角色轮流追问） | 新 |

### G. 训练决策层（项目灵魂）

| 功能 | 说明 |
|---|---|
| 信号采集与归一 | 专注时长（带质量等级）、任务完成率、面试逐题得分、题目难度、距上次练习天数 |
| 掌握度模型 | 每个「用户 × 方向」维护 `mastery`，指数遗忘 + 练习增益，见 §6 |
| 遗忘曲线驱动出题 | 掌握度衰减到阈值以下的方向，自动掺入复习题（复习题占比可配） |
| 薄弱方向反推考察重点 | 专注投入低 + 得分低 + 覆盖题量少的方向加权提升考察密度 |
| 难度与追问深度映射 | 输出 `方向权重 / 难度分布 / 追问层数 / 复习题占比 / 阶段时长偏置` |
| 保护规则 | 样本量不足、时长数据质量差、单方向过载时禁止调整，并在面板写明原因 |
| 「凭什么这么考我」面板 | 每次决策的输入快照、规则链、结论全落库 |
| 决策反驳 | 用户点「这条不对」→ 修正掌握度并留痕，形成反馈信号 |
| 闭环效果验证 | 离线可复现 A/B（决策层选题 vs 随机选题，固定题库与模型），公布脚本与得分差 |

### H. AI 智能体

| 功能 | 来源 |
|---|---|
| 学习计划工作流：先分析学习数据 → 生成个性化计划，关键步骤请求确认 | S |
| Agent 执行可观测：run / step / toolcall / decision / approval 全链路留痕 | S |
| 长期记忆：自动提取用户偏好并注入后续上下文（启用需真实数据积累，见 §16 P5） | S |
| 每周学习报告、每日总结、进度提醒 → 通知中心（每日去重、Markdown 渲染） | S |
| 定时任务调度 + 模型档位自动降级（LOW 档跑摘要类任务） | S |

### I. 面试日程管理

| 功能 | 来源 |
|---|---|
| 邀请智能解析：规则 + AI 双引擎，支持飞书/腾讯会议/Zoom，提取公司/岗位/时间/链接 | G |
| 日历日/周/月视图 + 拖拽调整 + 列表视图 | G |
| 状态流转、定时过期、手动标记、面试提醒 | G |
| **面试前突击入口**：日程临近 → 自动按该公司岗位方向生成一次 10 分钟短模拟 | 新 |

### J. 账号、模型与用量

| 功能 | 来源 |
|---|---|
| 注册/登录/会话；IdentityProvider 可切换（本地账号 / 平台账号 / 单机免登录） | S+M改造 |
| 多 LLM Provider 管理（OpenAI 兼容协议），默认聊天/向量/评分模型页面可切 | G |
| BYOK 与平台代持双模式；**Chat / Embedding / ASR / TTS 四类用途可分别配不同 Provider 与 Key**（不假设同源） | G+新 |
| **不内置任何模型 API Key**：仓库、镜像、seed 数据中均不得出现可用 Key | 新 |
| 每用户每日 token 上限、超额熔断、用量报表 | S+新 |
| 头像预签名直传 + 历史头像回滚 | S |
| 管理员重置密码脚本（scrypt，无需邮件服务） | S |
| **模型与 Prompt 版本留痕**：会话记录模型 ID + prompt hash + 评估器版本，趋势图仅在可比区间内连线 | 新 |
| **CLI 运维面**：`annona export --user` / `reindex` / `eval` / `reset-password` | 新 |

### K. 工程与部署

| 功能 | 来源 |
|---|---|
| 可重复 `@RateLimit`（Global/IP/User 维度）+ Redis Lua | G |
| Resilience4j 熔断降级、分布式 SingleFlight 请求合并 | M |
| 多级线程池隔离（通用 / AI-IO / CPU 密集 / 查询），显式 `ThreadPoolExecutor` | M |
| Redis Stream 异步模板（简历分析、向量化、出题、报告）+ 失败重试与死信 | G |
| Flyway 迁移 + `ddl-auto: validate` | G |
| S3/RustFS 对象存储、SpringDoc OpenAPI、统一 `Result<T>`、全局异常 | G |
| Docker Compose 一键部署（PG+Redis+S3对象存储(Silo)+App+Nginx）+ CI（typecheck/lint/test） | G+S |
| 纯逻辑单测：分块死循环、向量序列化契约、结构化输出边界、HTML 净化白名单 | S |
| **RAG 评测与压测脚本**：Recall@K / MRR、并发延迟分位数 | M |

---

## 5. 领域模型

### 5.1 关键约束：方向主数据（`direction`）

这是整套系统的地基。面试方向、任务科目、专注记录、题库、掌握度**必须引用同一份方向字典**，否则「学习行为 → 面试决策」只能靠字符串猜。

```text
direction
  id           PK   代理键（gen_random_uuid）；业务表外键指向本列
  key                例 'java-concurrency' / 'kb-408-os'(知识库派生)；owner 内唯一
  name                展示名
  parent_id           层级（'java-backend' -> 'java-concurrency'）
  origin        enum   SKILL_BUILTIN | KNOWLEDGE_BASE | USER_CUSTOM | JD_PARSED
  kb_doc_id           派生自哪个知识库（origin=KNOWLEDGE_BASE 时）
  status        enum   ACTIVE | ARCHIVED
  user_id               USER_CUSTOM 归属；内置与知识库派生为 NULL
  meta_json           难度分布、阶段模板、SKILL 摘要
```

- **主键是代理键 `id`，`key` 只在 owner 内唯一**（`UNIQUE NULLS NOT DISTINCT (user_id, key)`，PG15+ 语法）：
  两个用户都应该能建一个叫「刑法学」的 `USER_CUSTOM` 方向；内置方向（`user_id IS NULL`）之间仍靠
  这一条约束保持 `key` 全局唯一。第一版把 `key` 当全局主键，本阶段已修正（见
  `specs/2026-09-25-direction-master-data-adr.md` 的修订记录）。
- 因此**所有业务表的方向列一律外键到 `direction.id`**（存 `key` 字符串无法定位 owner）。
- 打卡/番茄钟结束时的科目 = **方向下拉 + 可即时新建**，新建即以 `USER_CUSTOM` 落字典，之后可一键升级为「绑定知识库」的方向。
- 用户不可见的内置方向（JD 解析临时产生）30 天后回收。

### 5.2 核心表清单

| 表 | 作用 | 关键字段 |
|---|---|---|
| `app_user` | 账号 | id, email(citext, **活跃用户内唯一**), password_hash, password_algo, role, created_at, deleted_at |
| `direction` | 方向字典（§5.1） | id PK, key, name, parent_id, origin, status, kb_doc_id, user_id |
| `study_session` | 一次专注/学习会话 | user_id, **direction_id**, start_at, end_at, minutes, mode(POMODORO/IMMERSIVE/CHECKIN), quality |
| `study_event` | 会话内原子事件 | session_id, type(START/BLUR/FINISH/INTERRUPT), at, payload |
| `checkin` | 每日打卡 | user_id, day, hours, mood, energy, note, snapshot_url |
| `study_plan` / `plan_task` | 计划与任务 | plan_id, direction_id, title, day_number, status, source(用户/AI/决策层) |
| `todo_item` | 今日待办 | user_id, day, title, done, origin |
| `mastery` | 方向掌握度 | user_id, **direction_id**, mastery, confidence, last_practiced_at, sample_size, updated_at |
| `knowledge_doc` | 文档元数据 | user_id, title, source_type, s3_key, status, chunk_count, content_hash |
| `doc_chunk` | 切片与向量 | doc_id, seq, text, token_len, embedding vector(1024), tsv tsvector |
| `question_bank_item` | 题库 | direction_id, difficulty, stem, ref_answer, key_points, rubric, followups, source_chunk_id, status, hit_rate |
| `interview_session` | 面试会话 | user_id, direction_id, stage_plan, status, total_score, **chat_model, evaluator_model, prompt_hash, evaluator_version** |
| `interview_message` | 问答流水 | session_id, role, content, question_id, turn_no, followup_depth |
| `evaluation_result` | 评估结果 | session_id, question_id, score, weighted_score, dims_json, raw_json, fallback_used |
| `decision_trace` | 决策留痕（可解释面板） | user_id, scene(MOCK_INTERVIEW/CRAM/PLAN), inputs_json, rules_hit_json, output_json, rejected_by, created_at |
| `voice_session` | 语音会话 | session_id, asr_provider, tts_provider, vad_config, e2e_latency_p50/p95 |
| `interview_schedule` | 面试日程 | company, position, start_at, meeting_url, source_text, status, reminded |
| `agent_run/step/tool_call/decision/approval` | 智能体留痕 | run_id, parent_step, tool, args_hash, result, needs_approval |
| `user_memory` | 长期记忆 | user_id, kind(PREFERENCE/FACT/GOAL), content, confidence, source_run_id |
| `notification` | 通知 | user_id, type, title, body_md, read, dedup_key |
| `model_provider` | 模型服务配置 | id, user_id(nullable=平台级), name, provider_type, base_url, supports_embedding, enabled |
| `model_key` | 按用途加密存储的 Key | provider_id, **usage**(CHAT/EMBEDDING/ASR/TTS/EVALUATOR), nonce, ciphertext, kek_version, scope(SYSTEM/USER), created_at |
| `model_default` | 默认模型选择 | user_id, default_chat, default_embedding, default_evaluator, default_asr, default_tts |
| `token_usage` | 用量记账 | user_id, scene, model, prompt_tokens, completion_tokens, cost_cent, day |
| `retrieval_eval_run` | 检索评测 | query_set, top_k, recall_at_k, mrr, latency_p95, backend(pgvector/es) |

所有业务表含 `user_id` 并建立 `(user_id, …)` 复合索引；向量列统一 1024 维、COSINE、HNSW。

### 5.3 身份与账号存储（用户信息到底落在哪）

三类存储的职责边界先钉死：

| 存在哪 | 存什么 | 不存什么 |
|---|---|---|
| **PostgreSQL** | 身份主体、资料、口令哈希、审计型会话记录、登录失败计数、数据导出/删除请求 | 明文 token、明文口令、活跃会话令牌 |
| **Redis** | 活跃会话令牌（TTL 滑动）、单机模式的临时态、匿名共学在线状态、限流计数、验证码 | 任何需永久保留的用户资产 |
| **S3** | 头像字节、上传的原始文档、导出的数据包、PDF 报告 | 结构化业务数据（库里只存 object key） |

identity 模块表清单：

```text
app_user              身份主体。id(uuid), email(citext, 活跃行内唯一), password_hash,
                      password_algo('scrypt'), status(ACTIVE|PENDING_VERIFY|LOCKED|DELETED),
                      role(USER|ADMIN), created_at, deleted_at
user_profile          可编辑资料，1:1 分开（更新频率与列宽差异大）。user_id, nickname,
                      avatar_object_key, bio, target_exam, timezone, theme_key,
                      onboard_cursor
user_session          会话的审计投影（非活跃令牌）。id, user_id, device, ip,
                      ua_hash, created_at, last_seen_at, revoked_at
auth_token            邮箱验证 / 密码重置 / 二次确认。PK(purpose, token_hash)，另给
                      token_hash 与 user_id 建索引；expires_at, consumed_at, ip
login_attempt         失败锁定与风控。key(email+ip), fail_count, locked_until, last_at
avatar_change         头像历史，支持一键回滚。id PK（回滚会重复插入同一 object_key），
                      user_id, object_key, created_at
user_data_request     导出与硬删除请求。user_id, type(EXPORT|DELETE), status,
                      file_object_key, requested_at, scheduled_purge_at, done_at
```

七条存储规则：

1. **口令用 scrypt**（`spring-security-crypto` 的纯 Java 实现，N=2^15/r=8/p=1，每用户随机 salt）。选它不选 Argon2 的唯一理由：Argon2 需 native 库，Windows 与多样 JDK 环境下安装失败率高，而自部署产品经不起“clone 下来编译不过”。
2. **活跃会话只进 Redis**（`ANNONA_SESSION` HttpOnly Cookie → `session:{token}`，7 天滑动 TTL）；`user_session` 表是**异步写的审计投影**，写失败不影响登录。这意味着 Redis 不是可选组件。
3. **任何一次性 token 只存哈希**（`auth_token.token_hash`），且**按 token 反查必须走索引**（PK 以 `purpose` 打头 + 单独 `(token_hash)` 索引；上一版 PK 以 `user_id` 打头，这类查询用不上任何索引）。枚举防护靠 `login_attempt` 的统一失败响应，不靠模糊文案。软删用户不得占住邮箱：`email` 唯一只对 `deleted_at IS NULL` 的行成立，否则 30 天宽限期内无法重新注册。
4. **v1 不做邮箱强制验证与第三方登录**。自部署环境常常没有 SMTP，`annona.identity.require-email-verification=false` 默认关；GitHub/微信 OAuth 延后到托管版需要时再加（表结构预留 `auth_token.purpose` 扩展）。
5. **删除是两段式**：`status=DELETED` 立即可见性归零，`user_data_request.scheduled_purge_at`（30 天宽限）到时才物理删。宽限期是为了给“误删 + 学习数据是用户资产”一个反悔窗口。
6. **单机免登录模式不拆表**：`annona.identity.mode=none` 时启动bootstrap 一个 `id=local` 用户，所有表仍带 `user_id`。这样从单机升到多人**零迁移**——否则早期用户的打卡数据全部要回填归属。
7. **模型 Key 不在 identity 表组**，在 `model_provider` / `model_key`（§12.1），`scope=SYSTEM` 时属平台所有，用户侧永不可读明文。

v1 **不存**的内容：真实姓名、手机号、身份证、学校/公司身份认证信息。产品不需要它们就能完成闭环，多存一类敏感信息就多一类泄露与合规义务。

---

## 6. 训练决策层设计

### 6.1 信号与质量分级

`study_session.quality` 由服务端判定（前端每 15s 心跳，失焦/休眠即停止累计）：

- `VERIFIED`：有心跳支撑的连续时长
- `PARTIAL`：心跳缺失后补上（切标签页、休眠唤醒）
- `SELF_REPORTED`：手动补录或纯打卡填的时长

**面板必须显示质量等级**，`SELF_REPORTED` 不进入难度调整计算，只进展示统计。这是防止「挂机 2h → 系统降难度 → 面板当面说谎」的唯一有效手段。

### 6.2 掌握度模型

每个 `用户 × 方向`：

```text
# 遗忘：距上次有效练习 t 天，半衰期 H（按方向类型配置，知识型短、技能型长）
decay(t)   = 0.5 ^ (t / H)

# 单次增益：得分按难度加权，追问深度越高权重越大
gain       = clamp((weighted_score - 0.5) * 2, -1, 1) * (1 + 0.15 * followup_depth)

# 指数滑动更新，样本越多越稳
mastery'   = clamp(mastery * (1 - lr) + (mastery * decay(t) + gain * k) * lr, 0, 1)
lr         = 0.35 - 0.02 * min(sample_size, 10)          # 冷启动快、后期稳
confidence = min(sample_size / 5, 1) * quality_weight     # 样本量与数据质量共同决定可信度
```

### 6.3 输出映射

决策层每次出题前产出一个结构化决定（同时落 `decision_trace`）：

```json
{
  "directionWeights": {"java-concurrency": 0.34, "kb-408-os": 0.28, "系统设计": 0.12},
  "difficultyDistribution": {"easy": 0.2, "medium": 0.5, "hard": 0.3},
  "followupDepth": 3,
  "reviewRatio": 0.25,
  "stageBias": {"项目深挖": "+8%", "反问": "-8%"},
  "reasons": [
    {"rule": "FORGETTING_CURVE", "target": "java-concurrency", "detail": "上次练习 12 天前，掌握度 0.71→0.58"},
    {"rule": "WEAK_DIRECTION", "target": "kb-408-os", "detail": "近 14 天专注占比 6%，专项面试 3 题错 2 题"},
    {"rule": "SAMPLE_GUARD", "target": "系统设计", "detail": "样本量 2 < 3，本次不调整难度"}
  ]
}
```

### 6.4 保护规则（必须有，否则第一次自相矛盾就失去信任）

1. `sample_size < 3` 的方向不许调整难度，只允许保持或标记「数据不足」。
2. 全部投入来自 `SELF_REPORTED` 时，决策退化为「按题库难度均匀出卷」，并在面板显示降级原因。
3. 单方向占比上限 40%，防止系统把用户关在一个方向里出不来。
4. 换模型或换 prompt 版本后，前 3 场面试只做基线采集，不与历史分数连线。
5. 用户点「这条不对」→ 该规则对该用户降权（`decision_trace.rejected_by`），累计 3 次驳回自动停用该规则。

### 6.5 效果验证（拒绝 n=1 伪统计）

不在 UI 上画「开启前后对比曲线」（样本量 1、题目难度不同，经不起质疑）。改为离线可复现实验：固定题库 + 固定模型 + 固定用户行为轨迹，跑「决策层选题」vs「随机选题」各 N 场，公布脚本、逐维得分差、Recall@K 与 token 成本。这张图放 README。

---

## 7. 知识库与检索

**ETL 流水线**：上传 → S3 → Tika 解析 → 结构感知分块（标题/段落边界优先，句末切分兜底，重叠滑窗，死循环保护上限）→ 内容 hash 幂等 → Embedding 批量入库 → 状态机 `PENDING/PARSING/CHUNKED/EMBEDDING/READY/FAILED` → Redis Stream 驱动，进度 SSE 推前端。

**混合检索**（两条通道并行，RRF 融合，再重排）：

- 语义通道：pgvector HNSW，COSINE，TopK=20，相似度阈值可配
- 关键词通道：`doc_chunk.tsv tsvector` + GIN。**中文分词在应用层做**（jieba/HanLP 词典，入库时写分词结果，查询时同分词器），检索配置统一 `simple`；短查询或专有名词命中不足时用 `pg_trgm` 模糊匹配兜底。不依赖 PG 内置 parser 对中文分词，也不需要为此换镜像或装扩展编译。
- 融合：RRF（k=60）→ 取 Top8 → 用同一向量模型算 query-doc 余弦重排 → 截断 TopK=4
- 降级：两通道均低于阈值 → 查询改写重试一次 → 仍不足则联网搜索（标注「非本地资料」）

**Retriever SPI**：`Retriever`（`PgVectorRetriever` 默认，`EsRetriever` 可选实现），`@ConditionalOnProperty` 切换，配套 `retrieval_eval_run` 表记录 Recall@K/MRR/延迟，用于真实 A-B 而非「感觉变好了」。默认不部署 ES——四件套中间件会毁掉「clone 下来能跑」。

---

## 8. 面试引擎

`SKILL.md` 是「方向的定义」，不是「题库」。格式约定：

```markdown
---
key: java-concurrency
name: Java 并发
parent: java-backend
difficultyCurve: [0.2, 0.5, 0.3]
stages: [自我介绍, 技术考察, 项目深挖, 反问]
kbHints: ["Java并发编程的艺术", "juc 源码"]
evaluationFocus: [正确性, 原理深度, 工程取舍]
---
## 考察范围
## 常见追问路径
## 评分侧重
```

非技术方向只需一份方法论 SKILL（结构化面试、STAR 行为面、无领导单人扮演版）+ 用户知识库，系统即可出题。

**出题链路**：决策层输出 → 方向配额 → 题库检索（同方向同难度，向量相似 + 题干关键词双重去重已问题目）→ 不足则实时从知识库生成 → 组卷（阶段×难度×主问题/追问）。

**评估链路**：分批（每批 N 题）→ `StructuredOutputInvoker` 结构化输出（失败带错误信息重试，最多 2 次）→ 二次汇总（总分/雷达/建议）→ 降级兜底（抽取失败时保留逐题原文并标记 `fallback_used`）。文字与语音共用同一引擎，结果可横向比较。

**评分可比性**：逐题分按题目难度加权后再进总分；总分与历史比较时必须同 `evaluator_model + prompt_hash`。实测答对率回写 `question_bank_item.hit_rate`，用于难度自动校准（出题时标 0.6 难，实际人人答对 → 降为 0.4）。

---

## 9. 自习室与体验层

前端是这个项目「被喜欢」的部分，不做约束性设计，只钉三条实现原则：

1. **3D 小岛是数据的可视化，不是桌面宠物**：生长阶段由「连续天数 × 累计时长 × 方向覆盖度」共同决定，不同方向种成不同植物——让「你练的都长出来了」是字面意义可见的。`three.js` + react-three-fiber，懒加载，移动端降级为 2D 插画。
2. **动效与音效必须可全关**，并遵守 `prefers-reduced-motion`。
3. **场景主题是 CSS token 包**（颜色、字体、环境音、背景素材），不允许逐页写分支——否则每加一个页面成本 ×3。

匿名共学状态用 Redis ZSET + TTL（成员心跳 30s 过期）暴露一个只读接口，前端 10s 轮询。不建房间协议、不做消息通道、不做任何用户可输入内容的地方。

---

## 10. 语音面试

WebSocket 上行 PCM（AudioWorklet 采集，24k/16k 单声道）→ 服务端流式 ASR + VAD 断句 → 会话上下文 → LLM 流式 → **句子级并发 TTS**（首句优先，边合成边推流）→ 下行音频块 + 字幕事件。回声防护（AI 播放期间自动半双工）+ 手动提交模式（无耳机场景）。暂停/恢复与超时自动暂停。

Micrometer 埋点：ASR 首字、LLM 首 token、TTS 首包、端到端（用户停止说话 → 首包音频）P50/P95。端到端延迟是验收指标；「TTS 首包 200ms」只是链路中的一段，不作为对外数字。

---

## 11. 技术架构

```text
                    ┌──────────────────────────────────────┐
   Browser (React/Vite) ── Nginx ── REST /api/*  (Result<T>)
        │                     └─ SSE /api/stream/*  (问答/进度)
        │                     └─ WS  /ws/voice      (语音面试)
        └──────────────────────────────────────────────────┘
                                    │
   ┌────────────────────────────────┴───────────────────────────────┐
   │  modules/（16 个业务模块，详见项目结构文档 §4）                  │
   │   identity │ study │ plan │ knowledge │ retrieval │ qa          │
   │   questionbank │ interview │ evaluation │ planner │ voice       │
   │   schedule │ agent │ usage │ resume │ notify                    │
   │   + shared/{direction,signal,domain,idempotent}  跨模块内核      │
   ├─────────────────────────────────────────────────────────────────┤
   │  config/    全局装配（web/async/persistence/security/properties） │
   │  cli/       运维命令面（export / reindex / eval / seed）          │
   │  bootstrap/ 启动校验（缺 KEK 即拒绝启动）                        │
   ├─────────────────────────────────────────────────────────────────┤
   │  annona-spi：IdentityProvider│ModelProvider│Retriever│          │
   │              LearningSignalReader│DecisionRule（契约，零 Spring） │
   ├─────────────────────────────────────────────────────────────────┤
   │  annona-infrastructure：file(S3) redis llm crypto pdf tokenizer  │
   │  annona-common：result exception enums util annotation            │
   └─────────────────────────────────────────────────────────────────┘
        │                │                 │                │
   PostgreSQL+pgvector  Redis(缓存/Stream/限流/在线)   S3/RustFS   外部模型
```

**模块边界原则**：`planner`（决策层）不属于 `interview`。面试、计划工作流、突击入口三方都消费它；它对上只暴露 `InterviewPlan advise(DecisionContext)`，对下只读 `mastery / study_session / evaluation_result`。所有跨模块消费优先走 SPI 接口而非直接依赖，保证可剥离、可 A/B。

**包结构**：Maven 按依赖层次切 5 个模块（`annona-common` / `annona-spi` / `annona-infrastructure` / `annona-server` / `annona-web`），业务代码全部在 `annona-server` 内的 `io.annona.modules.*`，模块内自包含 `controller/service/repository/entity/dto/mapper`。完整包树与 ArchUnit 约束见 [annona-项目结构.md](./annona-项目结构.md)。

**与初稿的四处结构调整**（已同步到项目结构文档，理由见 `specs/2026-09-25-module-granularity-adr.md`）：

| 调整 | 原因 |
|---|---|
| `report` 拆开：评估入 `evaluation`，报告聚合入 `interview/report`，PDF 导出下沉 `infrastructure/export` | 原 `report` 同时装着算法与技术适配，是典型杂物包 |
| 新增 `evaluation` 顶级模块 | 文字与语音面试共用评估引擎；若放在 `interview` 内会造成 `voice → interview` 反向依赖 |
| 新增 `qa` 模块 | 知识库写侧（ingest/chunk/embed）、读侧（retrieval）、交互侧（会话与流式）三者变更频率与资源模型完全不同 |
| 新增 `resume`、`notify` | 各自有独立异步链路与生命周期（简历分析走 Redis Stream；通知有去重与定时推送） |

**关键工程约束**：Controller 只做路由与校验；`@Transactional` 只在 Service 且范围最小；LLM / S3 / 外部 HTTP 调用**严禁**进入事务；业务异常统一 `BusinessException(ErrorCode.X, msg)`；**异常出口分两类：业务失败返回 HTTP 200 + `Result.error(code, msg)`（前端靠 `code` 分流），而路由/传输层错误（404 / 405 / 400 / 500）返回真实 HTTP 状态码 + 同样的 `Result` 响应体**——全压成 200 会让监控看不到故障、还会把 SPA 未做 fallback 的 404 伪装成“成功但数据不对”；不返回 Entity 给前端（MapStruct 映射）；构造器注入 + `@RequiredArgsConstructor`；2 空格缩进、无通配符 import；SLF4J 占位符且异常作为最后参数。

---

## 12. 非功能设计

| 维度 | 方案 |
|---|---|
| 限流 | 可重复 `@RateLimit`（Global/IP/User/接口 维度），Redis Lua 令牌桶；出题与评估接口按 token 配额二次限流 |
| 熔断降级 | Resilience4j：模型调用熔断 + 降级到备选 Provider/档位；TTS 不可用时降级为文字面试 |
| 请求合并 | 分布式 SingleFlight（Redis SETNX + 本地短暂缓存），护 Embedding 与重复检索 |
| 线程池 | 显式 `ThreadPoolExecutor` 四类：通用 / AI-IO（大队列长超时）/ CPU 密集（分块）/ 查询；禁止 `Executors.newXxx` |
| 异步一致性 | Redis Stream 消费前校验实体存在，已删除则 ACK 丢弃；交卷与评估回写带幂等键（`session_id + evaluator_version`） |
| 可观测 | Micrometer 指标（模型延迟、token、ASR/TTS 延迟、队列积压）+ 结构化日志 + `trace_id` 贯穿异步链路 |
| 安全 | 见 §12.1 密钥模型；预签名直传、HTML 净化白名单、SQL 全参数化、CSP、密码 scrypt、无用户 UGC 广播 |
| 隐私 | 所有学习数据可一键导出/删除（CLI + UI）；语音原始音频默认不持久化，仅存转写文本 |
| 成本 | `token_usage` 按场景记账；摘要/周报走 LOW 档模型；每日配额超额熔断并告知用户 |
| 测试 | 纯逻辑单测（分块/序列化/结构化边界/净化）+ Service 层 Mockito + 限流真实 Redis 集成测试；`docker compose` 冒烟；RAG 评测脚本纳入 CI 可选阶段 |

### 12.1 密钥模型（API Key）

**原则：项目不内置任何可用的模型 API Key。** 代码、配置文件、Docker 镜像、seed 数据、测试夹具中均不得出现真实 Key；`.env.example` 只给占位符，真实 `.env` 必须进 `.gitignore`。

来源分三种：

| 场景 | 谁付钱 | 注入方式 | 落地形式 |
|---|---|---|---|
| 自部署 · BYOK | 用户 | 设置页表单提交 | AES/GCM 加密后存 `model_key`（`nonce` + `ciphertext`） |
| 自部署 · 单机免登录 | 用户 | `.env` / 环境变量 | 启动 bootstrap 加密入库，UI 只显示 masked |
| 官方托管 · 平台代持 | 平台 | 运维环境变量 / 密钥管理系统 | `scope=SYSTEM` 记录，用户侧永不可读明文 |

三条硬约束：

1. **KEK 与密文分离**：加密主密钥来自环境变量 `ANNONA_SECRET_KEY`（可接 KMS/Vault），不与密文同库存储；`kek_version` 字段支持轮换重加密。
2. **生产 profile 缺 KEK 即启动失败**（fail-fast）。**不采用上游 interview-guide 的 `DEV_FALLBACK_KEY` 静默兜底**——那条路径会让「忘配环境变量」也启动成功，等日后补配真 KEK 时存量密文全部解不开。dev 环境需要 fallback 时，只在 `application-dev.yaml` 里显式声明一个明文写死的开发密钥，且该 profile 不得进生产镜像。
3. **明文 Key 永不下发前端**：所有响应只返回 `maskedApiKey`（形如 `sk-****abcd`）；日志与异常栈中 Key 必须脱敏；Key 只在服务端调用模型时解密，且解密结果不进入任何缓存。

**用途维度**：Chat / Embedding / ASR / TTS / Evaluator 五类各自独立配置（`model_key.usage`）。允许「DeepSeek 出题 + 本地 LM Studio 做 Embedding + 讯飞做 TTS」的组合，也允许像 DashScope 那样一个 Key 登记到多个 usage。此维度必须在 P0 建表时落定，否则 P3 语音接入要改表结构。

**托管模式的额外约束**：代持模式下成本由平台承担，因此**必须先有 `token_usage` 记账 + 每用户每日配额 + 超额熔断**才允许开放注册（否则批量注册即可打穿账单）。「免费额度具体数值」留作运营决策，不影响代码结构。

---

## 13. 部署形态（双轨）

| | 自部署（开源内核） | 官方托管 |
|---|---|---|
| 身份 | `LocalIdentityProvider`（或单机免登录） | 平台账号 |
| 模型 | `BYOKModelProvider`（用户填 Key） | 平台代持 + 免费额度 + 每日 token 上限 |
| 检索 | pgvector 默认 | pgvector 默认，ES 可选 |
| 语音 | 用户自备 ASR/TTS Key | 平台提供，默认关闭按量开放 |
| 许可 | AGPL-3.0 | AGPL-3.0（托管亦提供完整修改后源码下载） |

`docker compose up -d` 必须能拉起 PG(+pgvector) + Redis + S3 兼容对象存储（PGSTY Silo，见 s3-storage-silo-adr） + App + Web，并带 `seed` 出 1 个演示用户、1 份内置技术 SKILL、1 篇示例讲义。README 首屏承诺「5 分钟看到第一场模拟面试」，同时 README 明确标注基于/参考了哪些上游开源项目（AGPL 合规 + 社区观感）。

---

## 14. 里程碑与验收物

| 期 | 范围 | 可验证产出（截图物） |
|---|---|---|
| **P0 骨架** | 仓库、Flyway、Docker Compose、`Result<T>`/异常/日志、IdentityProvider+ModelProvider+Retriever 三 SPI、CI | `docker compose up` 后首页 200；一次模型 ping |
| **P1 闭环内核** | `direction` 字典 + 学习会话（心跳+质量分级）+ 知识库 ETL + 混合检索 + SSE 问答 + Skill 出题（含知识库派生方向）+ 评估引擎 + **决策层 v1 与可解释面板** + PDF 报告 | 上传讲义 → 生成方向 → 面试一场 → 面板说出「为什么考你这题」→ 导出报告 |
| **P2 自习室体验** | 番茄钟/打卡/热力图/趋势 + 3D 小岛 + 沉浸模式 + 主题包 + 匿名共学状态 + 计划任务联动 | 真数据热力图 + 小岛生长前后对比 |
| **P3 语音面试** | WS 全链路 + VAD + 句子级并发 TTS + 回声防护 + 延迟基线报告 | P50/P95 端到端延迟表 + 一场语音面试记录 |
| **P4 日程与智能体** | 邀请解析 + 日历 + 突击入口 + 计划工作流 + 通知中心 + token 记账与降级 | 一封真实面试邀请 → 日历 → 自动短模拟 |
| **P5 记忆与优化** | 长期记忆（需数周真实数据后启用）+ 错题沉淀闭环 + 离线 A/B 实验报告 | README 放真实决策案例 + 实验曲线 |

排序依据是依赖关系而非重要性：决策层需要方向字典与得分数据；3D 岛需要稳定采集；语音需要评估引擎先稳。长期记忆刻意放最后——没有真实学习数据的记忆功能，第一周就会暴露成空壳。

---

## 15. 明确不做（写进 README Non-goals）

1. **神态/表情/坐姿分析** —— 人脸数据合规成本，准确性无法验证，是减分项。
2. **群聊、@AI、任何形式的用户生成内容广播** —— 上线即意味着审核、举报、垃圾治理，单人维护者扛不住；共学状态已提供「有人在学」的感知。
3. **多人群面无领导小组** —— 与上条同源，改为 AI 多面试官压力面。
4. **徽章/积分/等级体系** —— 外部激励会稀释「数据驱动成长」主线（上游 summer-checkin 已自行删除该功能）。
5. **付费墙锁核心功能** —— 与开源获取关注的目标直接冲突。
6. **workspace / team / RBAC 多租户团队功能** —— `user_id` 隔离足够，团队方向是无底洞。
7. **移动端原生 App** —— Web 响应式优先，3D 降级 2D。
8. **自研模型 / 微调 / 向量模型训练** —— 只做编排与工程。
9. **Elasticsearch 作为默认检索后端** —— 四中间件会毁掉“clone 下来能跑”；`EsRetriever` 仅作为可选实现存在（理由与重新评估触发条件见 `specs/2026-09-25-storage-single-postgres-adr.md`）。

---

## 16. 风险与开放问题

| 风险 | 判断 | 应对 |
|---|---|---|
| 决策层「智能感」依赖数据量，冷启动期面板显得空洞 | 高 | P1 就上 `SAMPLE_GUARD`，明确显示「数据不足，暂不调整」；提供 `annona demo seed --weeks 6` 生成合成历史数据供演示与测试 |
| LLM 评分噪声被用户识破，主线可信度崩塌 | 高 | 版本留痕 + 难度加权 + 同题多模型对照公开分差，主动承认误差范围 |
| 中文关键词检索效果不及预期 | 中 | 应用层分词 + pg_trgm 兜底 + Recall@K 实测；必要时启用 ES SPI |
| 端到端语音延迟达不到可用体验 | 中 | P3 前用 `scripts/bench` 做前置验证，不达标则降级为「一键朗读答案 + 文字作答」 |
| 平台代持额度被打穿 | 高 | 代持模式必须晚于 `token_usage` 记账与超额熔断上线；注册加验证码/邀请制 |
| 用户误提交真实 Key 到 git | 中 | `.gitignore` 预置 + pre-commit 密钥扫描（gitleaks）+ CI 阶段拦截 |
| 内置 SKILL 内容过时 | 中 | SKILL 文件版本化 + 社区 PR 目录约定；README 明示「技术方向以社区维护为主」 |
| JDK 与 Spring AI 版本耦合 | 低 | 目标 Java 21（本地已就绪）；若某依赖要求 25，仅切换本机 JDK，不改架构 |
| 范围过大导致长期无产出 | 高 | 严格按 §14 分期，每期必须留下可截图物；P1 不完成不开 P2 |

---

## 17. 术语表

| 术语 | 含义 |
|---|---|
| 方向（direction） | 可被练习与考察的知识/技能单元，全局主数据 |
| 学习信号 | 专注时长（含质量等级）、任务完成率、面试得分、间隔天数四类输入 |
| 掌握度（mastery） | 用户对某方向的估计熟练度 ∈ [0,1]，由遗忘衰减 + 练习增益更新 |
| 决策留痕（decision trace） | 一次自动决策的输入快照、命中规则、输出结论，可解释面板的数据源 |
| 质量等级 | `VERIFIED / PARTIAL / SELF_REPORTED`，学习时长可信度标记 |
| 突击面试（cram） | 真实面试日程临近时自动生成的 10 分钟短模拟 |
