/**
 * questionbank 域前端类型（P1b-02/03）：与后端 DTO（QuestionBankController 响应）逐字段
 * 对齐，UUID 全 String、时间 ISO 字符串。
 */

/** 题目状态（与 QbQuestionEntity 常量一致；STALE 不设，见 skill-questionbank-adr 否决表）。 */
export type QbQuestionStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'

/** 出题任务状态（与 QbGenerationTaskEntity 一致）。 */
export type QbGenStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'

/** 追问（与后端 QbFollowUp record 同构）。 */
export interface QbFollowUp {
  question: string
  referenceAnswer: string | null
  keyPoints: string[] | null
  scoringRubric: string | null
}

/** 题目条目（QuestionBankController 列表/维护响应）。 */
export interface QbQuestion {
  id: string
  question: string
  topicSummary: string | null
  referenceAnswer: string | null
  keyPoints: string[] | null
  scoringRubric: string | null
  difficulty: number
  followUps: QbFollowUp[] | null
  status: QbQuestionStatus
  createdAt: string
}

/** 出题参数（POST generate 请求体）。 */
export interface GenerateQuestionsBody {
  difficulty: number
  questionCount: number
  followUpCount: number
}

/** 出题任务状态快照（generate 返回 + generation-status 轮询共用）。 */
export interface QbGenStatusResponse {
  taskId: string
  status: QbGenStatus
  difficulty: number
  questionCount: number
  followUpCount: number
  savedCount: number
  skippedCount: number
  message: string
  error: string
  updatedAt: string
}

/** SSE 进度信封（shared/progress.ProgressEvent，与 knowledge 进度同形）。 */
export interface QbProgressEvent {
  status: string
  stage: string
  processed: number
  total: number
  message: string
}

/** 容量校验：单个追问档位（selectable=false 的档位前端禁用）。 */
export interface CapacityOption {
  followUpCount: number
  availableQuestionCount: number
  selectable: boolean
}

/** 容量校验响应。 */
export interface CapacityResponse {
  followUpOptions: CapacityOption[]
}
