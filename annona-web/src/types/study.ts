/**
 * 与后端 `io.annona.modules.study.dto` 对齐的手写占位类型（P1a-04）。
 *
 * <p>同 `types/direction.ts` 的先例：`pnpm gen:api` 生效后由 `api.gen.ts` 接管，
 * 本文件届时删除。
 */

/** study_session.mode 取值（V2 chk_study_session_mode 同名子集；IMMERSIVE 属 P2 沉浸模式）。 */
export type SessionMode = 'POMODORO' | 'IMMERSIVE' | 'CHECKIN'

/** 质量分级三态（服务端判定；面板必须显示质量等级——设计 §6.1）。 */
export type SessionQuality = 'VERIFIED' | 'PARTIAL' | 'SELF_REPORTED'

/** 与 SessionResponse 对齐（endAt/minutes/quality 为 null = 进行中）。 */
export interface StudySession {
  id: string
  directionId: string
  mode: SessionMode
  /** ISO-8601 时间串（后端 timestamptz）。 */
  startAt: string
  endAt: string | null
  /** 服务端判定时长；进行中为 null。VERIFIED=心跳覆盖口径、PARTIAL/SELF_REPORTED=墙钟。 */
  minutes: number | null
  quality: SessionQuality | null
}

/** 与 CheckinResponse 对齐（GET /checkins/today 无打卡时接口直接返 null）。 */
export interface Checkin {
  id: string
  directionId: string
  /** YYYY-MM-DD（Asia/Shanghai 口径）。 */
  day: string
  hours: number
  mood: string | null
  energy: number | null
  note: string | null
  snapshotUrl: string | null
  createdAt: string
}

/** POST /api/study/sessions 请求体。 */
export interface StartSessionInput {
  directionId: string
  /** 1–240 分钟。 */
  plannedMinutes: number
  /** 缺省 POMODORO；IMMERSIVE 为沉浸模式（P2-03）。CHECKIN 不开放给本端点。 */
  mode?: SessionMode
}

/** POST /api/study/sessions/manual 请求体（quality 恒 SELF_REPORTED）。 */
export interface ManualSessionInput {
  directionId: string
  startAt: string
  endAt: string
}

/** POST /api/study/checkins 请求体（一天一条幂等 upsert）。 */
export interface UpsertCheckinInput {
  directionId: string
  /** 0–24 小时（0.5 步进）；0 = 收回补录，联动会话一并删除。 */
  hours: number
  mood: string | null
  /** 1–5。 */
  energy: number | null
  note: string | null
  snapshotUrl: string | null
}

/** GET /api/study/stats/overview 单日聚合（服务端只回有记录日，全年补零由前端做）。 */
export interface StatsDayMinutes {
  /** YYYY-MM-DD（Asia/Shanghai 日界）。 */
  day: string
  /** VERIFIED+PARTIAL 分钟（有效专注，与 planner 信号同口径）。 */
  verifiedMinutes: number
  /** SELF_REPORTED 分钟（含打卡联动，单独分列）。 */
  selfReportedMinutes: number
}

/** GET /api/study/stats/overview 方向聚合（已按有效时长降序；归档方向落占位名）。 */
export interface StatsDirectionMinutes {
  directionId: string
  name: string
  verifiedMinutes: number
  selfReportedMinutes: number
}

/** GET /api/study/stats/overview?year= 响应。 */
export interface StatsOverview {
  year: number
  days: StatsDayMinutes[]
  directions: StatsDirectionMinutes[]
  /** 累计打卡天数（全量口径，跨年——小岛解锁生长用）。 */
  totalCheckins: number
}

/** GET /api/study/presence 响应（匿名共学，只有计数无身份）。 */
export interface PresenceStatus {
  online: number
}
