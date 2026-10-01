import { request } from '@/api/request'
import type { DecisionTrace } from '@/types/decision'

/**
 * 可解释决策面板端点封装（P1c-06/07）。Result 拆包与 ApiError 转译由 `./request` 统一处理。
 * 归属校验在后端：非本人的会话/留痕返回空列表或 3200。
 */
export const decisionApi = {
  /** GET /api/decision/session/{id}：某场面试的全部决策留痕。 */
  sessionTraces(sessionId: string): Promise<DecisionTrace[]> {
    return request.get<DecisionTrace[]>(`/api/decision/session/${sessionId}`)
  },

  /** GET /api/decision/recent?limit=：首页最近 N 条决策摘要（时间倒序）。 */
  recent(limit = 5): Promise<DecisionTrace[]> {
    return request.get<DecisionTrace[]>(`/api/decision/recent?limit=${limit}`)
  },

  /** POST /api/decision/trace/{id}/reject：驳回一条决策，返回该规则累计驳回数。 */
  reject(traceId: string): Promise<number> {
    return request.post<number>(`/api/decision/trace/${traceId}/reject`)
  },
}
