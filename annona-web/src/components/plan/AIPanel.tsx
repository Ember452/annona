import { useEffect, useRef, useState } from 'react'

import { chatStream } from '@/api/plan'
import { Button } from '@/components/ui/button'
import MarkdownRenderer from '@/components/qa/MarkdownRenderer'
import { cn } from '@/lib/utils'

interface AIPanelProps {
  planId: string
  /** 编辑器当前选中片段（「问AI」引用条用）。 */
  selection: string | null
  onClearSelection: () => void
}

interface ChatTurn {
  role: 'user' | 'assistant'
  content: string
}

/**
 * 工作室 AI 面板（P2-06 三面板之三，借 🅢 ai-chat-panel）：流式回复（rAF 合帧渲染）、
 * 选区引用条（>600 字符服务端再截断）、宽窄两档 380/640。对话不落库——刷新即失
 * （plan-module-adr §决策 4 的 v1 口径）。
 */
export default function AIPanel({ planId, selection, onClearSelection }: AIPanelProps) {
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [wide, setWide] = useState(false)
  const [streamBuffer, setStreamBuffer] = useState('')
  const bufferRef = useRef('')
  const rafRef = useRef<number | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<(() => void) | null>(null)

  // 流式合帧：onDelta 高频触发，rAF 每帧只 flush 一次（qa 页同款节流）
  function appendDelta(delta: string) {
    bufferRef.current += delta
    if (rafRef.current !== null) return
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null
      setStreamBuffer(bufferRef.current)
    })
  }

  useEffect(() => () => {
    abortRef.current?.()
    if (rafRef.current !== null) cancelAnimationFrame(rafRef.current)
  }, [])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight })
  }, [turns, streamBuffer])

  function send(text: string) {
    const message = text.trim()
    if (!message || streaming) return
    setTurns((prev) => [...prev, { role: 'user', content: message }])
    setInput('')
    setStreaming(true)
    setStreamBuffer('')
    bufferRef.current = ''
    const assistantIndex = turns.length + 1
    abortRef.current = chatStream(planId, message, selection, {
      onDelta: appendDelta,
      onDone: () => {
        setTurns((prev) => {
          const next = [...prev]
          next[assistantIndex] = { role: 'assistant', content: bufferRef.current }
          return next
        })
        setStreamBuffer('')
        setStreaming(false)
      },
      onError: (code, errorMsg) => {
        setTurns((prev) => {
          const next = [...prev]
          next[assistantIndex] = { role: 'assistant', content: `⚠️ ${errorMsg}（${code}）` }
          return next
        })
        setStreamBuffer('')
        setStreaming(false)
      },
    })
  }

  function askSelection() {
    if (!selection) return
    send(`帮我理解/改进这段：\n\n${selection}`)
    onClearSelection()
  }

  return (
    <aside className={cn('glass-panel-strong flex shrink-0 flex-col rounded-2xl transition-[width] duration-300', wide ? 'w-[640px]' : 'w-[380px]')}>
      <div className="flex items-center justify-between border-b border-border/50 px-4 py-2.5">
        <h2 className="text-sm font-semibold">AI 助手</h2>
        <div className="flex items-center gap-2">
          <button className="text-xs text-muted-foreground hover:text-foreground" onClick={() => setWide((w) => !w)}>
            {wide ? '收窄' : '加宽'}
          </button>
        </div>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-3">
        {turns.length === 0 && !streaming && (
          <p className="py-10 text-center text-xs leading-relaxed text-muted-foreground">
            基于当前计划文档问答与改写。<br />选中编辑器文字可「问AI」。
          </p>
        )}
        <div className="flex flex-col gap-3">
          {turns.map((turn, index) => (
            <div
              key={index}
              className={cn(
                'max-w-[95%] rounded-xl px-3 py-2 text-xs leading-relaxed',
                turn.role === 'user' ? 'self-end bg-primary/15' : 'self-start bg-white/5',
              )}
            >
              {turn.role === 'assistant'
                ? <MarkdownRenderer text={turn.content} />
                : <p className="whitespace-pre-wrap">{turn.content}</p>}
            </div>
          ))}
          {streaming && (
            <div className="max-w-[95%] self-start rounded-xl bg-white/5 px-3 py-2 text-xs leading-relaxed">
              <MarkdownRenderer text={streamBuffer || '…'} />
            </div>
          )}
        </div>
      </div>

      {selection && (
        <div className="mx-3 mb-2 flex items-start gap-2 rounded-lg bg-primary/10 px-2.5 py-2 text-[11px] text-muted-foreground">
          <span className="line-clamp-2 flex-1">引用：「{selection}」</span>
          <button className="shrink-0 text-primary hover:underline" onClick={askSelection}>问AI</button>
          <button className="shrink-0 hover:text-foreground" onClick={onClearSelection}>×</button>
        </div>
      )}

      <div className="flex items-end gap-2 border-t border-border/50 p-3">
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              send(input)
            }
          }}
          rows={2}
          placeholder="基于计划文档提问…（Enter 发送）"
          className="min-h-0 flex-1 resize-none rounded-lg border border-input bg-transparent px-2.5 py-2 text-xs outline-none focus:ring-1 focus:ring-ring"
        />
        <Button size="sm" disabled={streaming || !input.trim()} onClick={() => send(input)}>
          {streaming ? '…' : '发送'}
        </Button>
      </div>
    </aside>
  )
}
