import { request } from '@/api/request'
import type { EvaluationReport } from '@/types/evaluation'

/**
 * evaluation 报告端点封装（P1b-06）。Result 拆包与 ApiError 转译由 `./request` 统一处理；
 * 3000（报告不存在/非本人）由调用方按需判 `ApiError.code`。报告页按 status 轮询。
 */
export const evaluationApi = {
  /** GET /api/evaluation/sessions/{id}/report：读某会话的评估报告（含逐题明细与汇总）。 */
  report(sessionId: string): Promise<EvaluationReport> {
    return request.get<EvaluationReport>(`/api/evaluation/sessions/${sessionId}/report`)
  },
}
