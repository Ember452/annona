# ADR: SKILL 注册表与知识库出题的数据模型、播种与结构化输出机制（P1b-01/02/03）

- 日期：2026-09-29 · 状态：Accepted
- 相关：[direction-master-data-adr](./2026-09-25-direction-master-data-adr.md) · [retrieval-hybrid-adr](./2026-09-28-retrieval-hybrid-adr.md) · [knowledge-ingestion-adr]（如存在）
- 借鉴：🅖 `modules/interview/skill`（双文件分层、`_shared` 公共池、路径白名单、提示词注入形态）、
  🅖 `modules/knowledgebase`（任务状态机 + taskId fencing + 双阈值恢复 + 容量校验算法 + TOCTOU 二次拦截）

## 背景

P1b-01/02/03 要落地三件事：① SKILL.md 驱动的内置方向注册表（开发计划 P1b-01）；② 从知识库
分块出题（主问题 + 参考答案 + 关键点 + 评分标准 + 追问，异步管道，P1b-02）；③ 题库维护与
容量校验（追问数为硬约束，P1b-03）。上游 interview-guide 两套机制可直接借鉴，但它是单租户 +
classpath 静态技能 + 题库全量替换的形态，与 annona 的多租户 direction 主数据表、"题目需可溯源、
用户可策展"的诉求有三处结构性不适配。另有两个跨任务复用件（结构化输出重试、SSE 进度信封）
的归属需要在本批一次定清，避免 P1b-06 二次返工。

## 决策

1. **SKILL 资源与 front-matter**：沿用上游双文件制——`skills/<key>/SKILL.md`（front-matter +
   persona 正文）+ 可选 `skill.meta.yml`（displayName/display/categories）。front-matter 扩为
   `key`（必填，= direction key，全小写-dashed）、`name`、`description`（必填）、`parent`
   （可选，派生技能指向父技能 key，加载期校验父存在）。注册表启动扫描 `classpath:skills/*/SKILL.md`
   （目录名仅作组织，身份以 front-matter `key` 为准），缺必填字段 / key 重复 / parent 悬空 →
   **启动失败**并报文件与字段（验收"缺字段有明确报错"的机器形态）。
2. **内置方向播种**：`shared/direction` 新增 `BuiltinDirectionSeeder`（ApplicationRunner）与
   `SkillDirectionCatalog` 端口（接口定义在 shared、实现在 modules/interview/skill，依赖方向
   合规）。幂等 insert-if-missing（`origin=SKILL_BUILTIN`、`user_id IS NULL`），已存在一律跳过
   （含 ARCHIVED——用户归档过的不复活，改名不回写）。
3. **V8 数据模型**（PG 方言，沿用 V6 约定）：
   - `qb_question`：`id UUID` / `user_id` / `direction_id FK` / `question` / `topic_summary` /
     `reference_answer` / `key_points JSONB` / `scoring_rubric TEXT` /
     `difficulty SMALLINT CHECK 1..5` / `follow_ups JSONB`（元素 = {question, referenceAnswer,
     keyPoints[], scoringRubric}，与主问题同构）/ `sources JSONB`（[{docId, chunkId, headingPath}]
     出题上下文快照）/ `status CHECK DRAFT|ACTIVE|ARCHIVED` / 审计列；
     索引 `(user_id, direction_id, status)`、`(direction_id, difficulty)`。
   - `qb_generation_task`：`id UUID`（即 taskId fencing token）/ `user_id` / `direction_id` /
     `status CHECK QUEUED|PROCESSING|COMPLETED|FAILED` / `config JSONB`（请求参数快照）/
     `saved_count` / `skipped_count` / `message` / `error` / `updated_at`；
     **partial unique index `(user_id, direction_id) WHERE status IN ('QUEUED','PROCESSING')`**——
     同一 (user, direction) 同时只允许一个在途任务（按用户隔离，两用户可并行给同一方向出题）。
4. **difficulty 用 SMALLINT 1–5**（设计文档口径"难度升到 4"），否决上游 `junior|mid|senior`
   字符串——决策层（P1b-07 难度加权、P1c 难度映射）需要数值口径。
5. **生成语义 = 按 (user, direction) 替换 DRAFT、保留 ACTIVE**：重新出题清掉旧草稿换新一批，
   用户已策展（ACTIVE）的题目不受影响。缺口提示沿用上游口径：`saved_count/skipped_count` +
   message；目标追问数存 `config` 快照，实际数由前端按 follow_ups 现算对比。
6. **`StructuredOutputInvoker` 提前到 `common/ai`（本批落地，P1b-06 直接复用）**：
   格式注入（BeanOutputConverter.getFormat() 拼进 system）→ 调用 → JSON 提取 → 解析失败带
   "严格 JSON + 上次错误"反馈重试（次数可配）→ 本地未转义引号修复。出题管道是同步 chat 的
   首个真实消费方（`ModelProvider.chat`，事务外，跑 aiIoExecutor）。
7. **`ProgressEvent` 从 `modules/knowledge/dto` 提升到 `shared/progress`**（信封冻结契约的
   唯一出处），knowledge 同步切换 import；questionbank 出题进度复用同一信封与 SSE Hub 模式。
8. **容量校验算法照搬上游**：可用追问数 = 题目 follow_ups 中 question 非空的条数；
   `selectable(N) = |{q : status=ACTIVE ∧ difficulty=X ∧ usableFollowUps(q) ≥ N}| ≥ mainQuestionCount`；
   后端按 0..5 逐档返回 `{followUpCount, availableQuestionCount, selectable}`，前端禁用不可选档并
   给"当前题量下最多可严格保证 M 个追问"的建设性文案；P1b-05 创建会话时二次校验（TOCTOU 闭环）。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| SKILL 热更新（WatchService / 刷新端点） | 内置技能变更伴随发版，重启即得；验收只要求"放文件无需改代码"，启动扫描已满足。YAGNI |
| 追问独立表 | 批 1 追问只读（抽题/展示），JSONB 同构最简；P1b-05 作答记录用 (question_id, follow_up_index) 定位，不 demands 独立表。何时重估：追问需要独立评分维度/独立检索时 |
| 生成任务状态挂 direction 表 | direction 是 shared 主数据，questionbank 跨模块写主数据列 = 边界违规；独立任务表还免去 direction 表膨胀 |
| 题库追加式（只增不删） | 重新出题的语义就是"换一批草稿"；追加会让草稿无限堆积。保留 ACTIVE 已守住用户策展成果 |
| difficulty 沿用上游三级字符串 | 决策层需要数值口径做加权与映射；字符串到数值的换算层是纯开销 |
| `sources` 做跨模块 FK（→ kb_doc/kb_chunk） | 快照语义（文档删除后题目仍在）；qa citations JSONB 已有同款先例，无 FK 才不连坐知识库 schema |
| STALE 状态 + 文档哈希自动比对 | 上游定义了从未写入；annona 无真实文档更新频率数据，先不建。触发：文档重建/更新流程落地时 |
| 成片搬运上游 `skills/` 与 `_shared/references/` 全部内容 | AGENTS §4：成片（跨文件）搬运需先征得用户同意。本批 SKILL.md 考法 persona 自写（技术方向内容属产品承诺），references 机制落地 + 1 个样例；批量搬运留待用户决定 |

## 后果与约束

- 后续新增技能 = 在 `resources/skills/` 加目录（SKILL.md 必填三字段），重启即生效；**不许**在
  代码里硬编码技能清单。
- `qb_*` 表的方向列一律 `direction_id` FK，禁止存 key 字符串（direction ADR 修订 2 重申）。
- LLM/检索调用一律在事务外（aiIoExecutor）；写库走最小短事务；对外只暴露安全失败文案（不透传
  模型原始报错）。
- `ProgressEvent` 字段语义再变更必须同步本 ADR 与 knowledge 侧（信封冻结契约）。
- 错误码段位：questionbank = 2600–2699，interview = 2700–2799，按序续用。

## 何时重新评估

- 内置方向数量 >50 导致启动扫描/播种可见变慢（当前 13 个，毫秒级）。
- 出现多实例部署的 seed 竞争症状（当前 insert-if-missing 幂等 + 单实例假设；唯一约束兜底）。
- 用户要求技能在线编辑/租户级自定义技能（届时注册表从 classpath 扩展到 DB 加载，front-matter
  字段可直接搬为列）。
- 追问需要独立评分维度或进入检索视野（JSONB → 独立表迁移，届时一次性 V<sub>n+1</sub>）。

## 后续修订

- 2026-09-29（V8 实现时）：§决策 3 的在途任务唯一索引从 `(direction_id)` 细化为
  `(user_id, direction_id)`——内置方向（user_id NULL）被多用户共享，按 direction 单列
  会让两个用户不能同时给同一内置方向出题；任务隔离本就按用户语义设计。
- 2026-09-29（实现时）：§决策 6 的 Invoker 落点从 `common/ai` 改为 `shared/ai`——
  依赖方向 modules→spi→common，common 看不见 spi 的 ModelProvider；shared 允许依赖
  spi + common，消费方不变。
