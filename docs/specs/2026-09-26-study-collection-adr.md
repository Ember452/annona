# ADR: study 采集的数据模型与质量分级（心跳服务端判定）

- 日期 / 状态：2026-09-26 / Accepted · 任务 P1a-04 · 依赖 [direction-master-data-adr](./2026-09-25-direction-master-data-adr.md)（修订 2 遗留义务随本任务落实）

## 背景

P1a-04 建 `study_session` / `study_event` / `checkin` 三表。上游 summer-checkin 的采集链路（`/api/focus/sessions` 只收 `durationMinutes + sessionId`）**完全没有质量分级**——时长由前端上报，无法区分「真专注 2h」与「挂机 2h」。而设计文档 §6.1 明确：`SELF_REPORTED` 不进难度调整计算，这是防止「挂机 2h → 系统降难度 → 面板当面说谎」的唯一有效手段。因此判定逻辑是 annona 原创（借鉴地图 P1a-04 行已标注"判定逻辑自写并有测试"），判定的可信度取决于：时长必须由服务端权威计算，前端只提供"活着"的证据。

心跳的语义由此确定：**心跳不是数据同步，是活着的证明**。设计 §5.2 的事件枚举（START/BLUR/FINISH/INTERRUPT）本就不含 HEARTBEAT——心跳时间线只服务质量判定这一个消费方。

## 决策

1. **心跳时间线存 Redis ZSET**（key `study:hb:{sessionId}`，score=心跳时刻，TTL 2h 自清）。端口 `io.annona.common.study.HeartbeatTimeline` 定义在 common（纯 JDK 签名），Redisson 实现在 `annona-infrastructure.cache`（根 pom 决策：Redis 客户端依赖只落在 infrastructure，`annona-server` 编译期看不到 Redisson；`SessionStore` 同款装配，非 AutoConfiguration 条件装配）。前端每 15s 一次；失焦（`visibilitychange` hidden）暂停心跳并上报 BLUR 事件，恢复可见自动续跳。挂机/休眠表现为心跳 gap。
2. **quality 由服务端判定，前端上报的分钟数一律不采信**。`finish` 时服务端读完整心跳时间线，`QualityGrader`（`modules/study/quality`，纯函数）按下述算法产出 quality 与 minutes（唯一权威定义）：
   - 相邻心跳 gap ≤ 60s 计入覆盖，否则该段丢失（不计时长）；首尾各补 15s；`covered = Σ有效gap + 30s`；
   - `maxGap` = 最大相邻间隔（无心跳时 = 墙钟时长）；
   - 无心跳 → `SELF_REPORTED`；`maxGap ≥ 30min` → `PARTIAL`（验收条：挂机 30 分钟无心跳 → PARTIAL）；否则 `VERIFIED`；
   - minutes 口径：`VERIFIED` = 覆盖时长；`PARTIAL` = 墙钟（"心跳缺失后补上"）；`SELF_REPORTED` = 用户输入。防欺诈靠 quality 标记 + P1c 决策侧消费，不靠扣时长。
3. **`study_session.checkin_id` 可空外键**：打卡 `hours > 0` 时同事务联动落一条 `mode=CHECKIN`、`quality=SELF_REPORTED` 的会话，打卡更新时按 `checkin_id` 同步——study_session 是时长的单一真相源，P1c 信号聚合只读它。
4. **打卡 `UNIQUE (user_id, day)` 幂等 upsert**：当天已存在则更新（mood/energy/note/hours/direction），拒绝语义改为幂等更新。
5. 手动补录 = `POST /api/study/sessions/manual`，`quality` 恒 `SELF_REPORTED`，`mode=CHECKIN`；方向校验走 `DirectionQueryService.existsVisibleTo`（modules 禁碰 `shared.direction.repository`，ArchUnit 已锁）。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 心跳写 DB 列（last_heartbeat_at，每次 UPDATE） | 15s 一条 UPDATE 写放大；只有末次心跳，中间 gap 历史丢失，无法判「挂机后恢复」 |
| 前端上报 minutes（上游模式） | 可伪造，直接击穿"面板不当面说谎"防线；上游无质量分级需求才允许此捷径 |
| study_event 加 HEARTBEAT 类型 | 违反设计 §5.2 事件枚举；存储随会话时长线性膨胀；无读取场景 |
| 打卡允许一天多条（Prisma 上游形状） | 统计口径被切碎、upsert 无法定义；上游自己也在 UI 层用"今天已经来过了"挡了多条，annona 把约束下沉到 DB UNIQUE |
| 心跳实现放 modules/study（org.redisson 直接注入） | Redisson 依赖按根 pom 决策只在 annona-infrastructure，server 编译期看不到 Redisson（mvnw verify 实测编译失败）；改为 common 端口 + infrastructure 实现（SessionStore 同款），但不做 AutoConfiguration 条件装配——Redis 是必选依赖而非性能项，无需 Fake |

## 后果与约束

- `study_session` 无 status 列：`end_at IS NULL` 即进行中；未 finish 的 RUNNING 会话原样展示，不写定时任务收尾（YAGNI），用户随时可 finish，服务端按时间线判定。
- 阈值常量只存在于 `QualityGrader`（GAP_TOLERANT=60s / HB_COVER=15s / PARTIAL_THRESHOLD=30min）；调整阈值 = 修改本 ADR + 单测同步，禁止散落。
- 所有时间计算用服务端 `Instant`；唯 `manual` 补录取用户给的 startAt/endAt（其 quality 恒 SELF_REPORTED，无欺诈面）。
- "今日"口径固定 `Asia/Shanghai`；per-user 时区（user_profile.timezone）推迟到有海外用户需求时。
- `mode` 枚举含 `IMMERSIVE`（设计 §4.A 长时段专注）但 P1a-04 只落 POMODORO/CHECKIN 两条生产路径，IMMERSIVE 留 P2。
- `study_event` 不冗余 `user_id`（破结构文档「业务表必含 user_id」约定，评审 C2）：它是会话作用域子表，只经 `session_id` 访问、随 `ON DELETE CASCADE` 清理，当前无按 user 直查事件的读路径。若 P1c 需按用户查事件再补列（届时数据模型变更触发本 ADR 修订）。

## P1c 开工前必须拍板（评审遗留语义，本批只实现不定夺）

- **B1 PARTIAL 的 minutes = 墙钟**会把「主动暂停 / 离席 / 合盖休眠」计成学习时长（暂停 40min 再恢复 → 一条 65min 的 PARTIAL），「挂机 2h → 降难度」的防线可能从这个口子漏回。设计 §6.1 只规定 SELF_REPORTED 不进难度调整，对 PARTIAL 沉默；且服务端无法区分「用户主动暂停」与「离席」。P1c 决策 ADR 必须定：PARTIAL 是否计入、按覆盖还是墙钟、要不要按 gap 时长打折（倾向覆盖口径）。
- **B2 孤儿 RUNNING 会话无收尾**：`findToday` 的 `or end_at is null` 让未结束会话永久挂在今日；无定时任务、无「一人同时一个 RUNNING」约束（前端 localStorage 挡，清存储即绕）。P1c 聚合须显式忽略 `end_at IS NULL`，并补收尾入口或「新 start 顶掉旧 RUNNING 按 PARTIAL 结算」规则。
- **B3 打卡联动会话 `start_at` 是合成值**（当日 00:00、`end_at`=00:00+hours），不可用于时段分布/热力图，否则打卡时长全堆在 00:00。P1c 消费前须知晓，或改用打卡实际提交时刻。
- **B4 手动补录与打卡联动共用 `mode=CHECKIN`**：P1c 若想按二者可信度不同分别对待，需加 `origin`/`source` 列——越早越便宜。
- **B5 planner 读 `study_session` 的跨模块入口未定**：`modules/study` 无对外只读服务，ArchUnit 仅禁了 `shared.direction.repository`，未禁 `modules/*.repository`。P1c-01 前须定「走 `StudySessionQueryService` 还是 SPI」并把禁令升级为通用跨模块规则，否则重演 P1a-03「直接注入他人 repository」。

## 何时重新评估

单机用户量级使 Redis ZSET 成为瓶颈（远期）；P2 沉浸模式需要长时段会话时复检 240min 上限与 60s gap 容忍（浏览器后台节流最坏 ≥1min/跳，若实测误伤则放宽 GAP_TOLERANT）。

## 本批未纳入（评审提示，另起任务）

- **覆盖率门禁（AGENTS「从 P1a 起生效」）尚未落地**：全仓 pom 无任何 jacoco 配置（评审 C3，P1a-03 已提过一次）。属跨模块构建决策（哪些模块、85% 清单 vs 60% 门槛），应独立 `build` 任务补，不夹带进采集批次。
- **前端零测试**：`usePomodoro` 408 行状态机是本批最易错处却无测试（评审 D4）。建议在 P1b 前单列任务引入 vitest（借鉴地图已把上游 `*.test.ts` 列为「最省事的验收清单」）。
