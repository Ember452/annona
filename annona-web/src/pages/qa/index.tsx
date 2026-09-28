import { useCallback, useEffect, useRef, useState, useTransition } from 'react'

import { askStream, qaApi } from '@/api/qa'
import MarkdownRenderer from '@/components/qa/MarkdownRenderer'
import ChunkPreviewDialog from '@/components/knowledge/ChunkPreviewDialog'
import type { QaCitation, QaMessage, QaMissReason, QaSession, QaSourcesEvent } from '@/types/qa'
import { toErrorMessage } from '@/lib/errors'

/**
 * 知识问答页（P1a-08）：会话列表 + 流式回答 + 引用跳转。
 *
 * <p>流式更新节奏：token 事件只累积到 ref，requestAnimationFrame 每帧合并一次并经
 * useTransition 降优先级提交——避免每个 delta 一次重渲染把 UI 卡死；渲染时每帧对
 * 合并后的全文整体重 parse（增量 parse 会撕裂 markdown 结构，见 qa-streaming-adr）。
 * 引用角标点击 → ChunkPreviewDialog 定位到对应块（P1a-08 验收：跳回原文段落）。
 */

/** 空命中原因的一行解释（"凭什么没找到"，AGENTS §1 可解释性的界面落点）。 */
const REASON_HINTS: Record<QaMissReason, string> = {
  MATCHED: '',
  NO_READY_DOC: '知识库中还没有就绪的文档——先到「知识库」上传资料并等待处理完成。',
  MODEL_MISMATCH: '已有文档的向量身份与当前模型不一致——在「知识库」对相关文档执行「重建」。',
  NO_MATCH: '资料里确实没有匹配到这个问题——换个问法，或补充相关资料。',
}

interface PreviewTarget {
  docId: string
  chunkIndex: number
}

/** 会话内的展示消息：历史消息 + 正在流式生成的虚拟消息共用一个形状。 */
interface ChatEntry {
  kind: 'history' | 'streaming'
  role: 'USER' | 'ASSISTANT'
  content: string
  completed?: boolean
  citations?: QaCitation[] | null
  /** 仅历史 ASSISTANT 行携带（V7 持久化）；流式期间走 sources 事件。 */
  missReason?: QaMissReason | null
}

export default function QaPage() {
  const [sessions, setSessions] = useState<QaSession[]>([])
  const [activeSessionId, setActiveSessionId] = useState<string | undefined>(undefined)
  const [messages, setMessages] = useState<QaMessage[]>([])
  /** 已发送但尚未从服务端拉回的提问（乐观渲染；不伪造 QaMessage 形状）。 */
  const [pendingQuestion, setPendingQuestion] = useState<string | null>(null)
  const [question, setQuestion] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [streamText, setStreamText] = useState('')
  const [sources, setSources] = useState<QaSourcesEvent | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<PreviewTarget | null>(null)
  const [, startTransition] = useTransition()

  const pendingTextRef = useRef('')
  const rafRef = useRef<number | null>(null)
  const abortRef = useRef<(() => void) | null>(null)
  const bottomRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    qaApi.sessions().then(setSessions).catch(() => setSessions([]))
    return () => abortRef.current?.()
  }, [])

  // 流式期间把滚动钉在底部；新回答/历史切换同样滚到底
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' })
  }, [messages, streamText, sources])

  const loadSession = useCallback(async (sessionId: string) => {
    setActiveSessionId(sessionId)
    setError(null)
    setSources(null)
    setStreamText('')
    try {
      setMessages(await qaApi.messages(sessionId))
    } catch (e) {
      setMessages([])
      setError(toErrorMessage(e, '历史加载失败'))
    }
  }, [])

  const startNewSession = useCallback(() => {
    setActiveSessionId(undefined)
    setMessages([])
    setError(null)
    setSources(null)
    setStreamText('')
  }, [])

  const flushPending = useCallback(() => {
    rafRef.current = null
    const next = pendingTextRef.current
    startTransition(() => setStreamText(next))
  }, [])

  const send = useCallback(() => {
    const text = question.trim()
    if (text === '' || streaming) return
    setQuestion('')
    setStreaming(true)
    setError(null)
    setSources(null)
    setStreamText('')
    setPendingQuestion(text)

    const appendDelta = (delta: string) => {
      pendingTextRef.current += delta
      if (rafRef.current == null) {
        rafRef.current = requestAnimationFrame(flushPending)
      }
    }
    const finish = async () => {
      if (rafRef.current != null) {
        cancelAnimationFrame(rafRef.current)
        rafRef.current = null
      }
      setStreaming(false)
      setPendingQuestion(null)
      pendingTextRef.current = ''
      // 落库后的权威状态从服务端拉回（新会话的 id 在这里才拿得到）
      try {
        const list = await qaApi.sessions()
        setSessions(list)
        const current = list[0]
        if (current != null) {
          setActiveSessionId((prev) => prev ?? current.id)
          setMessages(await qaApi.messages(activeSessionId ?? current.id))
        }
      } catch {
        // 刷新失败不影响已收到的回答（流式文本保留在界面上）
      }
      setStreamText('')
    }

    abortRef.current = askStream(activeSessionId, text, {
      onDelta: appendDelta,
      onSources: (event) => startTransition(() => setSources(event)),
      onDone: () => {
        void finish()
      },
      onError: (event) => {
        setError(event.message)
        void finish()
      },
    })
  }, [activeSessionId, flushPending, question, streaming])

  const entries: ChatEntry[] = [
    ...messages.map((message) => ({
      kind: 'history' as const,
      role: message.type,
      content: message.content,
      completed: message.completed,
      citations: message.citations,
      missReason: message.missReason,
    })),
  ]
  if (pendingQuestion != null) {
    entries.push({ kind: 'history', role: 'USER', content: pendingQuestion, completed: true })
  }
  if (streaming) {
    entries.push({ kind: 'streaming', role: 'ASSISTANT', content: streamText })
  }

  return (
    <section className="mx-auto flex h-[calc(100vh-8rem)] w-full max-w-5xl flex-col">
      <h1 className="font-heading text-2xl font-semibold tracking-tight">知识问答</h1>
      <p className="mt-1 text-sm text-muted-foreground">基于你的知识库检索回答；引用可点击跳回原文段落。</p>

      <div className="mt-4 flex min-h-0 flex-1 gap-4">
        <aside className="flex w-52 shrink-0 flex-col overflow-y-auto rounded-lg border bg-card">
          <button
            type="button"
            onClick={startNewSession}
            className="m-2 rounded-md border border-dashed px-2 py-1.5 text-sm text-muted-foreground hover:bg-muted"
          >
            + 新会话
          </button>
          {sessions.map((session) => (
            <button
              key={session.id}
              type="button"
              onClick={() => void loadSession(session.id)}
              className={`truncate px-3 py-2 text-left text-sm hover:bg-muted ${
                session.id === activeSessionId ? 'bg-muted font-medium' : ''
              }`}
              title={session.title}
            >
              {session.title}
            </button>
          ))}
          {sessions.length === 0 && (
            <p className="px-3 py-2 text-xs text-muted-foreground">还没有会话</p>
          )}
        </aside>

        <div className="flex min-h-0 min-w-0 flex-1 flex-col rounded-lg border bg-card">
          <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
            {entries.length === 0 && (
              <p className="py-8 text-center text-sm text-muted-foreground">
                提一个问题试试——回答只依据你上传的资料，并给出可跳转的引用。
              </p>
            )}
            {entries.map((entry, index) =>
              entry.role === 'USER' ? (
                <p
                  key={index}
                  className="ml-auto w-fit max-w-[80%] rounded-lg bg-primary px-3 py-2 text-sm text-primary-foreground"
                >
                  {entry.content}
                </p>
              ) : (
                <div key={index} className="max-w-[90%] rounded-lg border bg-background p-3">
                  {entry.content === '' ? (
                    <p className="text-sm text-muted-foreground">思考中…</p>
                  ) : (
                    <MarkdownRenderer text={entry.content} />
                  )}
                  {entry.kind === 'history' && entry.completed === false && (
                    <p className="mt-2 text-xs text-amber-600">回答中断，以上为已生成的部分内容。</p>
                  )}
                  {entry.kind === 'history' && entry.missReason != null && entry.missReason !== 'MATCHED' && (
                    <p className="mt-2 text-xs text-muted-foreground">{REASON_HINTS[entry.missReason]}</p>
                  )}
                  {entry.citations != null && entry.citations.length > 0 && (
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {entry.citations.map((citation, i) => (
                        <button
                          key={`${citation.chunkId}-${i}`}
                          type="button"
                          onClick={() => setPreview({ docId: citation.docId, chunkIndex: citation.chunkIndex })}
                          className="rounded border px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted"
                          title={`${citation.headingPath}（score=${citation.score.toFixed(2)}）`}
                        >
                          [{i + 1}] {citation.headingPath || `#${citation.chunkIndex}`}
                        </button>
                      ))}
                    </div>
                  )}
                  {sources != null && entry.kind === 'streaming' && sources.reason !== 'MATCHED' && (
                    <p className="mt-2 text-xs text-muted-foreground">{REASON_HINTS[sources.reason]}</p>
                  )}
                </div>
              ),
            )}
            {error && <p className="text-xs text-destructive">{error}</p>}
            <div ref={bottomRef} />
          </div>

          <div className="flex gap-2 border-t p-3">
            <textarea
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  send()
                }
              }}
              rows={2}
              placeholder="输入问题，Enter 发送（Shift+Enter 换行）"
              className="min-w-0 flex-1 resize-none rounded-md border bg-background px-3 py-2 text-sm outline-none focus:ring-1 focus:ring-ring"
            />
            <button
              type="button"
              onClick={send}
              disabled={streaming || question.trim() === ''}
              className="self-end rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-50"
            >
              {streaming ? '回答中…' : '发送'}
            </button>
          </div>
        </div>
      </div>

      <ChunkPreviewDialog
        docId={preview?.docId ?? null}
        onOpenChange={(open) => {
          if (!open) setPreview(null)
        }}
        highlightChunkIndex={preview?.chunkIndex ?? null}
      />
    </section>
  )
}
