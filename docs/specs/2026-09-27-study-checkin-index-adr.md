# ADR: study_session.checkin_id 的定位索引随 P1a-04 加固批补入 V3

- 日期：2026-09-27
- 状态：Accepted
- 相关：[2026-09-26-study-collection-adr.md](./2026-09-26-study-collection-adr.md)（打卡联动语义）、[2026-09-25-zero-migration-adr.md](./2026-09-25-zero-migration-adr.md)（迁移冻结，2026-09-26 起按修订口径执行）、代码：`V3__study_session_checkin_index.sql`、`CheckinService.syncLinkedSession`

## 背景

V2 的 `study_session.checkin_id` 是打卡联动的定位列：打卡 upsert（hours>0）按它找联动会话，`checkin` 行删除时的 `ON DELETE SET NULL` 级联也靠它定位子行。V2 头部自定约束"外键列必有索引"，但实际只建了 `(user_id, start_at)` 与 `(user_id, direction_id, start_at)` 两个索引，checkin_id 漏了。

P1a 阶段数据量小，PG 顺序扫描无感知；P1c 把 `study_session` 当时长唯一真相源后表按天增长，每个打卡请求一次 seq scan，打卡延迟随历史数据线性劣化。

同时复盘确认：V1 在被 CI 真实应用后曾被修改两次（`83a6772`、`8a5a39c`），"已应用迁移禁改"必须从约定升级为机检（pre-commit 已加），任何存量库修复与新结构都只能走新版本号。

## 决策

1. **索引补在 `V3__study_session_checkin_index.sql`**，不改 V2——即使"V2 从未进过生产库"，禁改规则也不允许按"CI 能绿"逐条豁免（规则的价值在于不用逐次判断）。
2. **V3 被 P1a-04 加固批占用，P1a-05 知识库表从 V4 开始**（顶部进度表已同步）。
3. 索引类型用普通 b-tree（`checkin_id` 等值定位），不建 unique——联动会话与打卡是 1..0..1 语义，但唯一约束应表达在写入路径（`syncLinkedSession` 的 upsert 判定），DB 层唯一化会把"历史脏数据修复"变成"插入即炸"，不值。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 直接修改 V2 补索引 | 迁移冻结：任何持有旧 V2 checksum 的持久库 `validate-on-migrate` 直接启动失败；CI 全是一次性库测不出来，正是最危险的静默差异 |
| 等到慢了再补（随 P1c） | 索引补建本身 30 秒，而"打卡接口随数据线性变慢"是上线后才发现的体验问题；加固批顺手做掉，避免 P1c 排期里混入运维动作 |
| 用 `(checkin_id) WHERE checkin_id IS NOT NULL` 部分索引 | 大部分行 checkin_id 为 NULL，部分索引确实更小；但本表量级（个人学习记录，万级行/年）下收益不可测，先取无谓词的最简形式，量级到了再评估 |
| 让 `syncLinkedSession` 改回按 `(day, direction)` 反查 | V2 列注释已明确否决该"脆匹配"（同日多会话/跨日补录下歧义） |

## 后果与约束

1. `db/migration/` 既有文件从本 ADR 起由 pre-commit 机检冻结（`--diff-filter=MD` 即拒）。
2. P1a-05 的迁移版本号是 **V4**（顶部进度表、任务表引用时以此为准）。
3. `FlywayBaselineIT` 的表清单断言不受影响（索引不产生新表）；它继续承担"无游离表"守卫。

## 何时重新评估

- `study_session` 单用户行数进入十万级且打卡 P95 劣化实测可感时，评估部分索引与 `(checkin_id, user_id)` 复合形态。
