/**
 * 可解释决策面板前端类型（P1c-06/07）：与后端 DecisionTraceResponse 逐字段对齐，
 * UUID 全 String、时间 ISO 串（evaluation/interview 类型同款口径）。
 */

/** 单条决策留痕。 */
export interface DecisionTrace {
  traceId: string
  sessionId: string
  directionId: string
  ruleKey: string
  action: string
  reason: string
  /** 被否决者 ruleKey；'USER' 表示用户已驳回；未否决为 null。 */
  rejectedBy: string | null
  createdAt: string
}
