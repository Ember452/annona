import { request } from '@/api/request'
import { createSseParser } from '@/api/qa'
import { MOCK_ENABLED } from '@/mocks/mockBackend'
import type { PlanDetail, PlanSummary, PlanTask, PlanTaskStatus, SplitResponse } from '@/types/plan'

/**
 * plan 计划与任务（P2-06）端点封装。Result 拆包与错误归一由 request 拦截器统一处理；
 * studio/chat 的 SSE 走 fetch（复用 qa 的 createSseParser），mock 模式在函数内短路——
 * mock 拦截点在 axios adapter，够不到 fetch（qa askStream 同款）。
 */
const BASE = '/api/plans'

export const planApi = {
  /** GET /api/plans：计划列表（含任务进度摘要）。 */
  list(): Promise<PlanSummary[]> {
    return request.get<PlanSummary[]>(BASE)
  },

  /** POST /api/plans：创建（document 为模板库内容，可空串）。 */
  create(input: { title: string; directionId?: string | null; document?: string }): Promise<PlanDetail> {
    return request.post<PlanDetail>(BASE, input)
  },

  /** GET /api/plans/{id}：详情（document + tasks + stale）。 */
  detail(planId: string): Promise<PlanDetail> {
    return request.get<PlanDetail>(`${BASE}/${planId}`)
  },

  /** PATCH /api/plans/{id}：改标题/文档（文档变更会使 stale 置真，见 plan-module-adr）。 */
  update(planId: string, input: { title?: string; document?: string }): Promise<PlanDetail> {
    return request.patch<PlanDetail>(`${BASE}/${planId}`, input)
  },

  /** DELETE /api/plans/{id}。 */
  remove(planId: string): Promise<void> {
    return request.delete<void>(`${BASE}/${planId}`)
  },

  /** POST /api/plans/{id}/split：AI 拆任务（同步，可能十几秒）。 */
  split(planId: string): Promise<SplitResponse> {
    return request.post<SplitResponse>(`${BASE}/${planId}/split`)
  },

  /** POST /api/plans/{id}/tasks：手动追加任务。 */
  addTask(planId: string, input: { title: string; directionId?: string | null; targetMinutes?: number }): Promise<PlanTask> {
    return request.post<PlanTask>(`${BASE}/${planId}/tasks`, input)
  },

  /** PATCH /api/plans/{id}/tasks/{taskId}：手动勾选（只改状态，进度归联动）。 */
  patchTask(planId: string, taskId: string, status: PlanTaskStatus): Promise<PlanTask> {
    return request.patch<PlanTask>(`${BASE}/${planId}/tasks/${taskId}`, { status })
  },

  /** GET /api/plans/tasks/today：今日待办（全部 PENDING，priority 降序）。 */
  today(): Promise<PlanTask[]> {
    return request.get<PlanTask[]>(`${BASE}/tasks/today`)
  },
}

export interface StudioChatHandlers {
  onDelta: (delta: string) => void
  onDone?: () => void
  onError?: (code: number, message: string) => void
}

/**
 * POST /api/plans/{id}/studio/chat：工作室 AI 对话（SSE 三事件 token/done/error）。
 *
 * @returns 中止函数
 */
export function chatStream(
  planId: string,
  message: string,
  selection: string | null,
  handlers: StudioChatHandlers,
): () => void {
  if (MOCK_ENABLED) {
    return mockChatStream(message, handlers)
  }
  const controller = new AbortController()
  void runChat(controller.signal, planId, message, selection, handlers)
  return () => controller.abort()
}

async function runChat(
  signal: AbortSignal,
  planId: string,
  message: string,
  selection: string | null,
  handlers: StudioChatHandlers,
): Promise<void> {
  try {
    const response = await fetch(`${BASE}/${planId}/studio/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ message, selection }),
      signal,
    })
    if (!response.ok || !response.body) {
      handlers.onError?.(1100, `助手服务不可用（HTTP ${response.status}）`)
      return
    }
    const parser = createSseParser((event) => {
      if (event.event === 'token') handlers.onDelta((JSON.parse(event.data) as { delta: string }).delta)
      else if (event.event === 'done') handlers.onDone?.()
      else if (event.event === 'error') {
        const payload = JSON.parse(event.data) as { code: number; message: string }
        handlers.onError?.(payload.code, payload.message)
      }
    })
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.end()
    handlers.onDone?.()
  } catch (error) {
    if (signal.aborted) return
    handlers.onError?.(0, error instanceof Error ? error.message : '连接中断')
  }
}

/** mock 模式：把消息本身回显成几段 token，走完 done（仅样式/交互开发用）。 */
function mockChatStream(message: string, handlers: StudioChatHandlers): () => void {
  const reply = `（mock）关于「${message.slice(0, 30)}」：这是本地模拟的助手回复，用于纯前端样式开发。`
  const chunks = reply.match(/.{1,6}/gs) ?? []
  let i = 0
  const timer = setInterval(() => {
    if (i < chunks.length) {
      handlers.onDelta(chunks[i++])
    } else {
      clearInterval(timer)
      handlers.onDone?.()
    }
  }, 60)
  return () => clearInterval(timer)
}
