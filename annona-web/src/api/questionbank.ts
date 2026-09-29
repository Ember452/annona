import { request } from '@/api/request'
import type {
  CapacityResponse,
  GenerateQuestionsBody,
  QbGenStatusResponse,
  QbQuestion,
  QbQuestionStatus,
} from '@/types/questionbank'

/**
 * questionbank（P1b-02/03）端点封装。Result 拆包与错误转译由 `./request` 拦截器统一处理；
 * 出题进度不走本模块——SSE 用原生 EventSource（cookie 随同源请求携带，见 pages/interview），
 * 轮询兜底打 `generationStatus()`。
 */
function base(directionId: string) {
  return `/api/questionbank/directions/${directionId}/questions`
}

export const questionbankApi = {
  /** POST /generate：发起异步出题，立即返回 QUEUED 任务状态。 */
  generate(directionId: string, body: GenerateQuestionsBody): Promise<QbGenStatusResponse> {
    return request.post<QbGenStatusResponse>(`${base(directionId)}/generate`, body)
  },

  /** GET /generation-status：最近一次任务状态（无历史时 data 为 null）。 */
  generationStatus(directionId: string): Promise<QbGenStatusResponse | null> {
    return request.get<QbGenStatusResponse | null>(`${base(directionId)}/generation-status`)
  },

  /** GET ""：题库列表（可空过滤）。 */
  list(directionId: string, params?: {
    status?: QbQuestionStatus
    difficulty?: number
    keyword?: string
  }): Promise<QbQuestion[]> {
    return request.get<QbQuestion[]>(base(directionId), { params })
  },

  /** GET /capacity：容量校验（追问数硬约束，0..5 逐档）。 */
  capacity(directionId: string, difficulty: number, mainQuestionCount: number): Promise<CapacityResponse> {
    return request.get<CapacityResponse>(`${base(directionId)}/capacity`, {
      params: { difficulty, mainQuestionCount },
    })
  },

  /** PUT /{id}/status：状态变更（DRAFT ↔ ACTIVE、任意 → ARCHIVED）。 */
  changeStatus(directionId: string, questionId: string, status: QbQuestionStatus): Promise<QbQuestion> {
    return request.put<QbQuestion>(`${base(directionId)}/${questionId}/status`, { status })
  },

  /** DELETE /{id}：物理删除（批 1 无作答记录）。 */
  remove(directionId: string, questionId: string): Promise<void> {
    return request.delete<void>(`${base(directionId)}/${questionId}`)
  },
}
