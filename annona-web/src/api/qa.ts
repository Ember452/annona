import { request } from '@/api/request'
import { MOCK_ENABLED } from '@/mocks/mockBackend'
import type { QaDoneEvent, QaErrorEvent, QaMessage, QaSession, QaSourcesEvent } from '@/types/qa'

/**
 * qa（P1a-08）端点封装。**流式不走 axios**：POST + SSE 用 fetch/ReadableStream 手解
 * （EventSource 只支持 GET 且不能带 body，qa-streaming-adr §决策 3；与知识库进度的
 * EventSource 用法并存，两种传输各配各的解析器）。mock 模式在 askStream 内短路——
 * mock 拦截点在 axios adapter，够不到 fetch。
 */
const BASE = '/api/qa'

export const qaApi = {
  /** GET /api/qa/sessions：当前用户会话列表（按最近活跃倒序）。 */
  sessions(): Promise<QaSession[]> {
    return request.get<QaSession[]>(`${BASE}/sessions`)
  },

  /** GET /api/qa/sessions/{sessionId}/messages：会话历史（含中断保留的部分内容）。 */
  messages(sessionId: string): Promise<QaMessage[]> {
    return request.get<QaMessage[]>(`${BASE}/sessions/${sessionId}/messages`)
  },
}

/** 流式提问的事件回调。onDelta 逐帧触发，合帧节流由调用侧（页面）负责。 */
export interface QaStreamHandlers {
  onSources?: (event: QaSourcesEvent) => void
  onDelta: (delta: string) => void
  onDone?: (event: QaDoneEvent) => void
  onError?: (event: QaErrorEvent) => void
}

/**
 * 发起一次流式提问（POST /api/qa/messages，text/event-stream）。
 *
 * @returns 中止函数（调用后停止读取流；服务端语义由后端保留部分内容，见 ADR §决策 8）
 */
export function askStream(
  sessionId: string | undefined,
  question: string,
  handlers: QaStreamHandlers,
): () => void {
  if (MOCK_ENABLED) {
    return mockAskStream(question, handlers)
  }
  const controller = new AbortController()
  void runAsk(controller.signal, sessionId, question, handlers)
  return () => controller.abort()
}

async function runAsk(
  signal: AbortSignal,
  sessionId: string | undefined,
  question: string,
  handlers: QaStreamHandlers,
): Promise<void> {
  try {
    const response = await fetch(`${BASE}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: sessionId ?? null, question }),
      signal,
    })
    if (!response.ok || !response.body) {
      handlers.onError?.({ code: response.status, message: `问答服务不可用（HTTP ${response.status}）` })
      return
    }
    const parser = createSseParser((event) => {
      if (event.event === 'sources') handlers.onSources?.(JSON.parse(event.data) as QaSourcesEvent)
      else if (event.event === 'token') handlers.onDelta((JSON.parse(event.data) as { delta: string }).delta)
      else if (event.event === 'done') handlers.onDone?.(JSON.parse(event.data) as QaDoneEvent)
      else if (event.event === 'error') handlers.onError?.(JSON.parse(event.data) as QaErrorEvent)
      // 未知事件类型忽略：契约演进（加字段/加事件）不破坏旧前端（ADR §重新评估）
    })
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.end()
  } catch (error) {
    // 主动中止不算错误；其余（网络断）转 error 事件——载荷形状与后端 error 事件一致
    if (signal.aborted) return
    handlers.onError?.({ code: 0, message: error instanceof Error ? error.message : '连接中断' })
  }
}

/** 解析出的一个 SSE 事件。 */
export interface SseEvent {
  event: string
  data: string
}

/**
 * SSE 帧解析器（纯字符串状态机，表驱动单测直接喂文本块）：
 * 事件以空行分隔，`event:`/`data:` 行累积，`:` 开头注释忽略；data 多行按 SSE 规范
 * 以 \n 拼接；end() 兜底上游省略末尾空行的情况。
 */
export function createSseParser(onEvent: (event: SseEvent) => void): {
  push: (chunk: string) => void
  end: () => void
} {
  let buffer = ''
  let eventName = ''
  let dataLines: string[] = []

  function dispatch(): void {
    if (dataLines.length === 0) return
    onEvent({ event: eventName === '' ? 'message' : eventName, data: dataLines.join('\n') })
    eventName = ''
    dataLines = []
  }

  /** 处理一行；返回 true 表示该行是空行（触发分发）。 */
  function processLine(line: string): boolean {
    if (line === '') return true
    if (line.startsWith(':')) return false
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      const value = line.slice(5)
      dataLines.push(value.startsWith(' ') ? value.slice(1) : value)
    }
    return false
  }

  return {
    push(chunk: string): void {
      buffer += chunk
      let newline = buffer.indexOf('\n')
      while (newline >= 0) {
        const line = buffer.slice(0, newline).replace(/\r$/, '')
        buffer = buffer.slice(newline + 1)
        if (processLine(line)) dispatch()
        newline = buffer.indexOf('\n')
      }
    },
    end(): void {
      // 流尾无换行的残行同样要处理（与后端 ofLines 的末行口径一致）
      if (buffer !== '') {
        const line = buffer.replace(/\r$/, '')
        buffer = ''
        if (processLine(line)) dispatch()
      }
      dispatch()
    },
  }
}

/** mock 模式的假流：延迟触发 sources/tokens/done，模拟逐字输出供样式开发。 */
function mockAskStream(question: string, handlers: QaStreamHandlers): () => void {
  const answer =
    `这是 mock 模式的示例回答（针对「${question}」）。\n\n- SSE 不经过 axios，短路在 api 层\n- 真实回答需要后端与 chat 模型\n\n代码示例：\n\n\`\`\`java\nSystem.out.println("hello");\n\`\`\`\n`
  const timers: number[] = []
  timers.push(
    window.setTimeout(() => handlers.onSources?.({ citations: [], reason: 'NO_READY_DOC' }), 100),
  )
  timers.push(
    window.setTimeout(() => handlers.onDelta(answer.slice(0, 40)), 200),
    window.setTimeout(() => handlers.onDelta(answer.slice(40)), 320),
    window.setTimeout(() => handlers.onDone?.({ messageId: 'mock-assistant' }), 400),
  )
  return () => timers.forEach((id) => window.clearTimeout(id))
}
