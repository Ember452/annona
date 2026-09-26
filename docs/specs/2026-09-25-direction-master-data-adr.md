# ADR: 引入 `direction` 方向主数据，禁止用自由文本对接科目与面试方向

- 日期：2026-09-25
- 状态：Accepted
- 相关：[../annona-项目设计文档.md](../annona-项目设计文档.md) §5.1

## 背景

产品主线是「自习室采集的学习行为 → 决定 AI 面试问什么」。这条链要求两侧的"方向"能对齐：学习记录要挂在某个方向上，掌握度按方向计算，题库按方向组织，出题按方向配额。

上游 summer-checkin 的现状是 `StudyRecord.subject` 与 `Checkin.subject` 均为 **nullable 自由文本**，`PlanTask` 只有 `category`（默认 `"study"`）。上游 interview-guide 侧的方向是 `SKILL.md` 的 skillKey。两者没有任何约束关系。

## 决策

新建 `direction` 主数据表（`key` / `name` / `parent_key` / `origin` / `kb_doc_id` / `meta_json`），`origin ∈ {SKILL_BUILTIN, KNOWLEDGE_BASE, USER_CUSTOM, JD_PARSED}`。

- 打卡、番茄钟会话、任务、题库题目、掌握度、决策留痕的方向字段**一律外键到 `direction.key`**。
- 采集侧 UI 不给自由文本输入框，改为「方向下拉 + 可即时新建」，新建即以 `USER_CUSTOM` 落字典，之后可升级为绑定知识库的方向。
- 放 `io.annona.shared.direction`（跨模块只读访问），不隶属于任何业务模块。
- JD 解析产生的临时方向 30 天后回收。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 继续用 subject 字符串 + 查询时模糊匹配 | 决策层的输入正确性无法保证，会出现"Java并发" / "java 并发" / "JUC" 三个方向各自积累数据；可解释面板第一条结论就能被用户证伪 |
| 只给采集侧加枚举下拉（不建表） | 非技术方向由用户知识库派生，是动态集合，枚举表达不了 |
| 用 `knowledge_doc` 直接充当方向 | 一个文档可能覆盖多个方向，一个方向也可能来自多个文档；多对多关系不该由主键承载 |
| 引入独立 taxonomy 表 + 关系表（严格本体建模） | 当前只有一层父子，多对多与多父继承是过度设计 |

## 后果与约束

1. **必须在 P1 第一天建表**，`Flyway V1__baseline.sql` 就要包含它。后补意味着所有历史学习数据要重新映射，而自由文本无法可靠回填。
2. 方向数量会随用户自建膨胀，需要 `parent_key` 聚合展示 + 上限（单用户自定义方向 ≤ 200，超出提示合并）。
3. 删除方向必须做级联策略：有历史数据的方向只能归档（`status=ARCHIVED`），不能物理删除，否则热力图与掌握度历史断裂。
4. 所有跨模块的方向参数类型统一为 `DirectionKey`（value object），禁止裸 `String` 传递（ArchUnit 可选规则）。

## 何时重新评估

- 若出现真正的多层学科树需求（考研专业课目录、法考章节树），再评估引入闭包表或 `ltree`；届时 `parent_key` 单层模型需要一次数据迁移，成本可控。

---

## 修订记录

### 2026-09-26｜主键改为代理键，方向命名空间改为 owner 内

**原决策的缺口**：本文原定“`direction.key` 做全局主键、业务表外键到 `key`”。这在多人场景下直接失败——
两个用户各自新建一个叫「刑法学」的 `USER_CUSTOM` 方向时，第一个人能建、第二个人会撞主键；
而“非技术方向由用户自定”是产品主张三的根基，不是边缘场景。同期还发现
`direction.user_id` 外键没带 `ON DELETE CASCADE`，用户物理删除会因孤立方向而失败。

**修正后**：

- `direction` 用代理键 `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`；
- `key` 以 owner 为命名空间：`UNIQUE NULLS NOT DISTINCT (user_id, key)`（PG15+）——
  内置方向（`user_id IS NULL`）之间仍保持全局唯一，不牺牲原有约束；
- 层级改用 `parent_id UUID REFERENCES direction(id)`；
- `user_id REFERENCES app_user(id) ON DELETE CASCADE`；
- **业务表方向列从 `direction_key` 改为 `direction_id`**（存 key 字符串无法定位 owner）。

**影响面**：本文“一律外键到 `direction.key`”与“决策参数用 `DirectionKey` 裸字符串”两处陈述以本修订为准；
设计文档 §5.1 / §5.2 已同步。因 V1 尚未在用户环境执行过，本次原地改 V1 而非开 V2（V1 头部已写下“一旦
有环境跑过就禁止再改”的规则）。`FlywayBaselineSqlSyntaxTest` 新增一条用例锁定“不得回到 key 当全局主键”的形状。

### 2026-09-26｜字典管理 API 同落 `shared.direction`，消费方仍只读

**原决策的缺口**：本文把 `io.annona.shared.direction` 定位为“跨模块只读访问”，暗示写侧应挂在某个业务
模块。但 direction 是被 6 个模块消费的主数据，挂任何单一模块都会制造错位耦合；而决策原文“采集侧 UI
可即时新建”本身就要求字典提供写路径。只读定位与首个消费方（P1a-03）同时出现，必须在落地前修正。

**修正后**：

- `shared.direction` 是 direction 主数据的**唯一属主**：管理 API（新建 / 归档 / 绑定知识库）与对
  消费模块的只读访问同落一包；
- 删除语义固化为“只归档、永不物理删”（后果 3 的实现路径）：不提供 DELETE 端点，重复归档幂等，
  “有历史数据只能归档”由不存在物理删路径天然满足；
- `USER_CUSTOM` 绑定 `kb_doc_id` 后 origin 单向升级为 `KNOWLEDGE_BASE`，不提供解绑（P1a-05 接入
  真实 kb_doc 表时再议）；绑定前仅做 UUID 格式校验，FK 由 P1a-05 补；
- 单用户自定义方向上限 200 为代码常量（后果 2），不做配置项；层级（parent_id）与 meta_json 暂不
  暴露 API（无 UI 需求，YAGNI）；
- 同 owner 内**按 `name` 判重**（ACTIVE 范围）：中文等非 ASCII 名称的 key 是随机段，
  `uq_direction_owner_key` 拦不住“同名不同 key”，下拉出现两个「刑法学」对用户是缺陷；
  显式 key / ASCII slug 撞名仍按 key 拦截。两者同用 2101，文案区分“同名方向已存在”/
  “该标识已被占用”。

**影响面**：结构文档 §4 `shared/direction` 条目已同步；业务表外键 `direction_id` 不变；`ErrorCode`
新增通用段 `DATA_CONFLICT(1006)`（并发窗口穿过预检查撞唯一约束时，`DataIntegrityViolationException`
处理器转 409，不再落 500）与 direction 段 2100–2199；设计文档 §5.1 `user_id` 行按本修订定稿——
`KNOWLEDGE_BASE` 方向由 `USER_CUSTOM` 升级而来、`user_id` 保留为本人（知识库文档本身私有，派生
方向不全局共享），仅内置方向为 NULL；本文 §后果 2 的 `parent_key` 与 §后果 4 的 `DirectionKey`
值对象两处陈述一并随修订 1 作废（层级已改 `parent_id`，跨模块引用已改 `direction_id`）。消费模块
的可见面不变——仍只读，写路径只属于字典自身的采集/管理 UI。

**遗留义务（后续任务的前置条件，不落实不算对应任务完成）**：

- **P1a-04 开工前**：`DirectionQueryService` 补 `existsVisibleTo(userId, directionId)` 单条可见性
  校验（采集写入 `study_session.direction_id` 前必须校验归属）；ArchUnit 增补一条规则禁止
  `modules..` 直接依赖 `shared.direction.repository..`（读路径只许经 QueryService）。
- **P1a-05**：补 `kb_doc_id` 外键时**必须同时补归属校验**（绑定前校验文档属主本人），只补 FK
  不校验归属视为未完成。
- **P1b-01 / P1c-01**：`annona-spi` 的 `PlannedQuestion.directionKey`（String）与“业务表外键
  `direction_id`”冲突；SPI 是对外发布 artifact、越晚改越是破坏性变更——在 SPI 首个真实消费方
  （决策层）落地前统一定稿为 `directionId`。
- **P1a-06 开工时**：接入 jacoco 插件（根 pom），使开发计划写定的覆盖率阈值（`chunk` ≥85%、
  后续 `planner/mastery` ≥85%、其余 60%）可被实际度量——此前只写了阈值未接入工具，阈值无法证伪。

### 2026-09-26｜主线校准：direction 服务两条平级产品线，学习信号是面试决策的可选增强而非前提

**原决策的缺口**：本文 §背景 第一句把「自习室采集的学习行为 → 决定 AI 面试问什么」当成产品主线与
本文前提。真实使用中（产品所有者亲自确认）：自习室与 AI 模拟面试是两个平级功能——有人用自习室背
政治、用 AI 面练后端（两侧方向不相交），有人用自习室备考面试科目（两侧相交）。旧表述把“相交场景
的增强”写成了“强制前提”，会诱导 P1c 按用户级总量做方向加权（背政治的时长污染后端配额），或把
纯面试用户（从不自习）误判为“无数据降级”。

**修正后**：

- direction 是用户的方向/科目主数据，服务自习室（统计维度）与面试（题库组织维度）两条**平级**
  产品线；共享字典只是主数据统一（一套 CRUD、一个选择器），不制造语义绑定；
- 两线的数据交互只有一条路径：**信号按 `direction_id` 聚合**。相交（该方向存在学习记录）时学习
  信号作为面试决策的**增强输入**；不相交时信号为空 = **正常形态而非降级**（SAMPLE_GUARD 与
  可解释面板均按既有规则工作，面板基于掌握度与错题给出解释）；
- 决策层输入分层（设计文档 §6.1 同步）：主输入 = 面试侧自身数据（掌握度/得分/错题/间隔天数），
  增强输入 = 学习信号；WEAK_DIRECTION 的专注占比只在该方向自身的学习记录内归一化，
  **禁止跨方向比较**；
- SPI 形状缺口（本修订直接后果）：`SignalSnapshot` 现为用户级聚合（无方向维度），无法表达
  按方向隔离——**P1c-01 开工时必须扩展 SPI 的方向维度**（见下方遗留义务）。

**否决的备选**：

| 备选 | 否决原因 |
|---|---|
| 彻底拆两套方向体系（study_subject + interview_direction 两表） | 改 V1（已被禁）、推翻本文、P1a-03 全部返工；且杀死相交场景（学 Java 并发 → 面 Java 并发）的自动增强——那是产品相对「番茄钟 + 随机面试」的核心差异。拆表唯一增量收益是“下拉不混显”，可在前端选择器加轻量过滤解决 |
| 给 direction 加用途字段（study/interview/both） | 用户建“Java 并发”时不应被迫回答用途；用途随生命周期演化（先学习后面试），字段会过期失效（YAGNI） |
| 保持旧主线不动（靠 SAMPLE_GUARD 兜底） | 背政治 + 练后端用户的 WEAK_DIRECTION 会按“专注占比恒低”错误加权；决策输入的主次语义含混，P1c 实现必然摇摆，且 SAMPLE_GUARD 兜底掩盖不了错误加权本身 |

**遗留义务（P1c-01 开工前，不落实不算该任务完成）**：

- 扩展 `annona-spi` 信号契约的方向维度：`SignalSnapshot` 增加按 direction 聚合的结构（或
  `LearningSignalReader` 增加 `readByDirection(userId, directionId, from, to)`），
  同步更新 `FakeLearningSignalReader` 与 `SpiFakesSmokeTest`；
- 实现口径：按 `direction_id` 聚合后，方向无学习记录时信号为空且决策链正常工作（golden 测试
  锁定该形态）。

**何时重新评估**：若“方向选择器下拉混显”（自习室看到面试方向、反之亦然）成为真实用户困扰，
在前端选择器加场景过滤（UI 层，不动数据模型）；若产品决定做“学习计划直接驱动面试”的强绑定
功能，需重开 ADR 推翻本修订。
