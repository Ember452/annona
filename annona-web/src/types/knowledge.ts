/**
 * knowledge 域前端类型（P1a-05）：与后端 DTO（KnowledgeDocController 响应）逐字段对齐，
 * UUID 全 String、时间 ISO 字符串。`pnpm gen:api` 生效后由 api.gen.ts 接管（request.ts 注释口径）。
 */

/** 文档状态机六态（与 KbDocEntity 常量一致）。 */
export type KbDocStatus = 'PENDING' | 'PARSING' | 'CHUNKING' | 'EMBEDDING' | 'READY' | 'FAILED'

/** 处理中的在途状态（进度订阅与轮询的停止条件取其补集）。 */
export const KB_IN_FLIGHT: KbDocStatus[] = ['PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING']

export interface KbDoc {
  id: string
  name: string
  directionId: string
  fileSize: number
  status: KbDocStatus
  processedChunks: number
  totalChunks: number
  chunkCount: number
  error: string | null
  /** 产生当前分块的算法版本（char-v2…）；与 detail.currentAnalyzerVersion 比对提示重建。 */
  analyzerVersion: string
  createdAt: string
}

export interface KbDocChunkView {
  index: number
  headingPath: string
  charStart: number
  charEnd: number
  content: string
}

export interface KbDocDetail {
  doc: KbDoc
  /** 服务当前的分块算法版本（Chunker.VERSION）。 */
  currentAnalyzerVersion: string
  chunks: KbDocChunkView[]
}

export interface UploadResult {
  id: string
  duplicate: boolean
  status: KbDocStatus
  message: string
}

/** SSE 进度事件 / 轮询 status 的统一形状（ProgressEvent 与 KbDocStatusResponse 同线径）。 */
export interface KbDocProgress {
  status: KbDocStatus
  stage: string
  processed: number
  total: number
  message: string
}
