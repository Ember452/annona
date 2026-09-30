import { request } from '@/api/request'
import type {
  CreateSessionBody,
  FinalizeView,
  SessionSummary,
  SessionView,
} from '@/types/interview'

/**
 * interview 会话端点封装（P1b-04/05）。Result 拆包与 ApiError 转译由 `./request` 统一处理；
 * 错误码语义（1001/2100/2604/2701/2702/2703）由调用方按需判 `ApiError.code`。
 */
export const interviewApi = {
  /** POST /api/interview/sessions：开始面试，返回含全部槽位的会话视图。 */
  create(body: CreateSessionBody): Promise<SessionView> {
    return request.post<SessionView>('/api/interview/sessions', body)
  },

  /** GET /api/interview/sessions：在途会话列表（directionId 空则跨方向）。 */
  listResumable(directionId?: string): Promise<SessionSummary[]> {
    return request.get<SessionSummary[]>('/api/interview/sessions', {
      params: directionId ? { directionId } : undefined,
    })
  },

  /** GET /api/interview/sessions/{id}：会话视图（断线重进；服务端缓存 miss 自动回落 DB）。 */
  get(sessionId: string): Promise<SessionView> {
    return request.get<SessionView>(`/api/interview/sessions/${sessionId}`)
  },

  /** POST /{id}/answers：单槽作答（追问含各自槽位；重复提交同槽拿 2703）。 */
  answer(sessionId: string, questionId: string, followUpIndex: number, answerText: string)
    : Promise<boolean> {
    return request.post<boolean>(`/api/interview/sessions/${sessionId}/answers`, {
      questionId, followUpIndex, answerText,
    })
  },

  /** POST /{id}/finalize：交卷（幂等；批 2 只落库不评分）。 */
  finalize(sessionId: string): Promise<FinalizeView> {
    return request.post<FinalizeView>(`/api/interview/sessions/${sessionId}/finalize`)
  },

  /** POST /{id}/abandon：放弃会话。 */
  abandon(sessionId: string): Promise<boolean> {
    return request.post<boolean>(`/api/interview/sessions/${sessionId}/abandon`)
  },
}
