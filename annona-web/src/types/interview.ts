/**
 * interview 会话域前端类型（P1b-04/05）：与后端 InterviewSessionController 视图逐字段
 * 对齐；UUID 全 String、时间 ISO 字符串（questionbank 类型同款口径）。
 */

/** 会话状态（与 InterviewSessionEntity 常量一致）。 */
export type SessionStatus = 'RESUMABLE' | 'COMPLETED' | 'ABANDONED'

/** 单个作答槽位（主问题 followUpIndex=0；追问 >=1）。 */
export interface SlotView {
  order: number
  questionId: string
  followUpIndex: number
  questionText: string
  answered: boolean
  answerText: string | null
}

/** 会话完整视图（开始/恢复/详情共用）。 */
export interface SessionView {
  id: string
  directionId: string
  status: SessionStatus
  currentIndex: number
  totalCount: number
  answeredCount: number
  slots: SlotView[]
  skippedReasons: string[]
  startedAt: string
}

/** 在途会话摘要（恢复列表项）。 */
export interface SessionSummary {
  id: string
  directionId: string
  status: SessionStatus
  currentIndex: number
  totalCount: number
  startedAt: string
}

/** 开始面试请求体（difficulties 长度必须等于 totalCount——后端 1001 校验口径）。 */
export interface CreateSessionBody {
  directionId: string
  totalCount: number
  difficulties: number[]
  followUpDepth: number
}

/** 交卷结果（批 2 只落库；评分与报告批 3 在此扩展）。 */
export interface FinalizeView {
  id: string
  answeredCount: number
  status: SessionStatus
  evaluatorVersion: string
}
