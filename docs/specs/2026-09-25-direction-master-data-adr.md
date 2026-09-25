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
