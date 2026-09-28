import { describe, expect, it } from 'vitest'

import { createSseParser, type SseEvent } from './qa'

/**
 * SSE 帧解析的行为规格（qa-streaming-adr §决策 3）：帧边界、跨 chunk 撕裂、
 * 注释/keep-alive、流尾补发。与后端 OpenAiSseDecoder 的测试同源——同一份 SSE
 * 规范的两端实现，用例口径保持一致。
 */
describe('createSseParser（fetch + ReadableStream 的手解帧）', () => {
  function collect(): { events: SseEvent[]; push: (chunk: string) => void; end: () => void } {
    const events: SseEvent[] = []
    const parser = createSseParser((event) => events.push(event))
    return { events, ...parser }
  }

  it('正常分帧：event + data + 空行触发分发', () => {
    const { events, push, end } = collect()
    push('event: token\ndata: {"delta":"你"}\n\nevent: done\ndata: {"messageId":"m1"}\n\n')
    end()

    expect(events).toEqual([
      { event: 'token', data: '{"delta":"你"}' },
      { event: 'done', data: '{"messageId":"m1"}' },
    ])
  })

  it('跨 chunk 撕裂：事件被网络分块从任意字节处切开仍能复原', () => {
    const { events, push, end } = collect()
    push('event: to')
    push('ken\ndata: {"del')
    push('ta":"好"}\n')
    push('\nevent: done\ndata: {')
    push('"messageId":"m1"}\n\n')
    end()

    expect(events).toEqual([
      { event: 'token', data: '{"delta":"好"}' },
      { event: 'done', data: '{"messageId":"m1"}' },
    ])
  })

  it('注释行（keep-alive）与无空格 data: 紧凑写法', () => {
    const { events, push, end } = collect()
    push(': keep-alive\ndata:{"delta":"a"}\n\n')
    end()

    expect(events).toEqual([{ event: 'message', data: '{"delta":"a"}' }])
  })

  it('上游省略末尾空行：end() 补发最后一个事件（后端 flush 同口径）', () => {
    const { events, push, end } = collect()
    push('event: token\ndata: {"delta":"尾"}')
    end()

    expect(events).toEqual([{ event: 'token', data: '{"delta":"尾"}' }])
  })

  it('error 事件原样透传载荷，交给调用侧映射文案', () => {
    const { events, push, end } = collect()
    push('event: error\ndata: {"code":2502,"message":"问答模型未配置"}\n\n')
    end()

    expect(events).toEqual([{ event: 'error', data: '{"code":2502,"message":"问答模型未配置"}' }])
  })
})
