/**
 * qa 域前端类型（P1a-08）：与后端 QaController 响应及 SSE 事件载荷逐字段对齐，
 * UUID 全 String、时间 ISO 字符串（与 types/knowledge 同纪律）。
 */

/** 结构化引用（与后端 QaCitation record 同形；score ∈ [0,1]）。 */
export interface QaCitation {
  docId: string
  chunkId: string
  chunkIndex: number
  headingPath: string
  snippet: string
  score: number
}

export interface QaSession {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface QaMessage {
  id: string
  type: 'USER' | 'ASSISTANT'
  content: string
  /** false = 流未正常结束（中断保留了部分内容），前端渲染"回答中断"标注。 */
  completed: boolean
  citations: QaCitation[] | null
  messageOrder: number
  createdAt: string
}

/** 空命中原因（后端 RetrievalMissReason；MATCHED = 有命中，无诊断含义）。 */
export type QaMissReason = 'MATCHED' | 'NO_READY_DOC' | 'MODEL_MISMATCH' | 'NO_MATCH'

/** SSE sources 事件载荷：引用列表 + 空命中诊断（qa-streaming-adr §决策 3）。 */
export interface QaSourcesEvent {
  citations: QaCitation[]
  reason: QaMissReason
}

/** SSE done 事件载荷。 */
export interface QaDoneEvent {
  messageId: string
}

/** SSE error 事件载荷（code = 后端 ErrorCode 数值，如 2502 模型未配置）。 */
export interface QaErrorEvent {
  code: number
  message: string
}
