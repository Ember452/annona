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
