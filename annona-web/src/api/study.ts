import { request } from '@/api/request'
import type {
  Checkin,
  ManualSessionInput,
  StartSessionInput,
  StudySession,
  UpsertCheckinInput,
} from '@/types/study'

/**
 * study 采集（P1a-04）八端点封装。
 *
 * <p>心跳与 finish 的语义：心跳只证明"活着"（15s 一次，后端只落 Redis 时间线）；
 * 分钟数由服务端按时间线判定，finish 返回的 minutes/quality 是唯一真相——前端
 * 本地倒计时只驱动展示，任何时长计算都不上报。Result 拆包、业务失败转 rejected
 * Promise、错误文案兜底均由 `./request` 的拦截器统一处理。
 */
const BASE = '/api/study'

export const studyApi = {
  /** POST /api/study/sessions：开始番茄钟（mode 固定 POMODORO）。 */
  startSession(input: StartSessionInput): Promise<StudySession> {
    return request.post<StudySession>(`${BASE}/sessions`, input)
  },

  /** POST /api/study/sessions/{id}/heartbeat：心跳（只落 Redis 时间线，无 DB 写）。 */
  heartbeat(sessionId: string): Promise<void> {
    return request.post<void>(`${BASE}/sessions/${sessionId}/heartbeat`)
  },

  /** POST /api/study/sessions/{id}/events：事件上报（后端仅接受 BLUR）。 */
  reportBlur(sessionId: string): Promise<void> {
    return request.post<void>(`${BASE}/sessions/${sessionId}/events`, { type: 'BLUR' })
  },

  /** POST /api/study/sessions/{id}/finish：结束（abandon=中途放弃）；二次调用幂等返回现结果。 */
  finishSession(sessionId: string, abandon: boolean): Promise<StudySession> {
    return request.post<StudySession>(`${BASE}/sessions/${sessionId}/finish`, { abandon })
  },

  /** POST /api/study/sessions/manual：手动补录（quality 恒 SELF_REPORTED）。 */
  createManualSession(input: ManualSessionInput): Promise<StudySession> {
    return request.post<StudySession>(`${BASE}/sessions/manual`, input)
  },

  /** GET /api/study/sessions/today：今日会话（含进行中，minutes/quality 为 null）。 */
  todaySessions(): Promise<StudySession[]> {
    return request.get<StudySession[]>(`${BASE}/sessions/today`)
  },

  /** POST /api/study/checkins：打卡幂等 upsert（同一天只有一条）。 */
  upsertCheckin(input: UpsertCheckinInput): Promise<Checkin> {
    return request.post<Checkin>(`${BASE}/checkins`, input)
  },

  /** GET /api/study/checkins/today：今天还没打过返回 null。 */
  todayCheckin(): Promise<Checkin | null> {
    return request.get<Checkin | null>(`${BASE}/checkins/today`)
  },
}
