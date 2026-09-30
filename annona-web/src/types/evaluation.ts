/**
 * evaluation 报告域前端类型（P1b-06/07 批 3）：与后端 EvaluationReportResponse 逐字段对齐，
 * UUID 全 String、时间 ISO 串（interview 类型同款口径）。`pnpm gen:api` 生效后由 api.gen.ts 接管。
 */

/** 报告状态（与 InterviewReportEntity 常量一致）。PENDING/RUNNING 时前端继续轮询。 */
export type ReportStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'

/** 逐题评估视图。 */
export interface QuestionEvaluation {
  questionId: string
  followUpIndex: number
  score: number | null
  feedback: string | null
  strengths: string[]
  improvements: string[]
  fallbackUsed: boolean
}

/** 整场汇总（DONE 时存在）。 */
export interface EvaluationSummary {
  strengths: string[]
  improvements: string[]
  overall: string
}

/** 评估报告响应（GET /api/evaluation/sessions/{id}/report）。 */
export interface EvaluationReport {
  sessionId: string
  status: ReportStatus
  evaluatorVersion: string
  compositeScore: number | null
  summary: EvaluationSummary | null
  questions: QuestionEvaluation[]
  chatModel: string | null
  evaluatorModel: string | null
  promptHash: string | null
  error: string | null
  generatedAt: string
}
