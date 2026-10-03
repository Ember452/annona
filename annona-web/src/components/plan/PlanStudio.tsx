import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import OutlinePanel from '@/components/plan/OutlinePanel'
import AIPanel from '@/components/plan/AIPanel'
import TaskDrawer from '@/components/plan/TaskDrawer'
import { Button } from '@/components/ui/button'
import MarkdownRenderer from '@/components/qa/MarkdownRenderer'
import { planApi } from '@/api/plan'
import { clearMarks, markMatches } from '@/lib/studio/find'
import { toErrorMessage } from '@/lib/errors'
import type { PlanDetail, PlanTask } from '@/types/plan'
import { cn } from '@/lib/utils'

const SAVE_DEBOUNCE_MS = 1200
/** 查找栏命中计数展示上限。 */
const FIND_HIT_LIMIT = 99

interface PlanStudioProps {
  plan: PlanDetail
}

type SaveState = 'saved' | 'dirty' | 'saving' | 'error'

/**
 * 文档工作室（P2-06 三面板，移植 🅢 MarkdownStudio 到 Vite + REST）：
 * 大纲（左）| 编辑器 + 预览（中，比例滚动同步）| AI 面板（右）；任务抽屉右缘覆盖。
 * 保存 = 1200ms 防抖 + Ctrl/Cmd+S + 卸载前 flush；Ctrl+F 预览内查找高亮（TreeWalker 打标）。
 * AI 对话不落库；选中片段经引用条进 AI 面板。
 */
export default function PlanStudio({ plan }: PlanStudioProps) {
  const [document, setDocument] = useState(plan.document)
  const [tasks, setTasks] = useState<PlanTask[]>(plan.tasks)
  const [stale, setStale] = useState(plan.stale)
  const [saveState, setSaveState] = useState<SaveState>('saved')
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [findOpen, setFindOpen] = useState(false)
  const [findQuery, setFindQuery] = useState('')
  const [findHits, setFindHits] = useState(0)
  const [selection, setSelection] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const documentRef = useRef(document)
  documentRef.current = document
  const editorRef = useRef<HTMLTextAreaElement>(null)
  const previewRef = useRef<HTMLDivElement>(null)
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const savingRef = useRef(false)

  const persist = useCallback(async () => {
    if (savingRef.current) return
    savingRef.current = true
    setSaveState('saving')
    try {
      const updated = await planApi.update(plan.id, { document: documentRef.current })
      setStale(updated.stale)
      setSaveState('saved')
    } catch (e) {
      setSaveState('error')
      setActionError(toErrorMessage(e, '保存失败，请重试'))
    } finally {
      savingRef.current = false
    }
  }, [plan.id])

  // 编辑入口：防抖保存（1200ms，上游同款）；saveState=dirty 是唯一真相
  const onDocumentChange = useCallback((next: string) => {
    setDocument(next)
    setSaveState('dirty')
    setStale(true)
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    saveTimerRef.current = setTimeout(() => void persist(), SAVE_DEBOUNCE_MS)
  }, [persist])

  // 卸载前 flush：防抖里的未保存内容不丢（上游 unmount flush 同款）
  useEffect(() => () => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    if (documentRef.current !== plan.document) {
      void planApi.update(plan.id, { document: documentRef.current }).catch(() => {})
    }
  }, [plan.id, plan.document])

  // Ctrl/Cmd+S 立即保存；Ctrl/Cmd+F 预览查找（在编辑器上接管原生行为）
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
        void persist()
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
        e.preventDefault()
        setFindOpen(true)
      }
      if (e.key === 'Escape') {
        setFindOpen(false)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [persist])

  // 查找高亮：查询变化即重打标（清旧标 → TreeWalker 打新标）
  useEffect(() => {
    if (!previewRef.current) return
    if (!findOpen || !findQuery.trim()) {
      clearMarks(previewRef.current)
      setFindHits(0)
      return
    }
    setFindHits(Math.min(FIND_HIT_LIMIT, markMatches(previewRef.current, findQuery)))
  }, [findQuery, findOpen, document])

  function scrollToLine(line: number) {
    const lines = document.split('\n')
    const headingText = (lines[line] ?? '').replace(/^#+\s*/, '').trim()
    if (!headingText || !previewRef.current) return
    const headings = previewRef.current.querySelectorAll('h1, h2, h3, h4, h5, h6')
    for (const heading of headings) {
      if ((heading.textContent ?? '').trim() === headingText) {
        heading.scrollIntoView({ behavior: 'smooth', block: 'start' })
        return
      }
    }
  }

  // 编辑器与预览的比例滚动同步（上游 ratio 同款：滚动比例映射，不追像素）
  function syncPreviewScroll() {
    const editor = editorRef.current
    const preview = previewRef.current
    if (!editor || !preview) return
    const editorRatio = editor.scrollHeight > editor.clientHeight
      ? editor.scrollTop / (editor.scrollHeight - editor.clientHeight)
      : 0
    preview.scrollTop = editorRatio * Math.max(0, preview.scrollHeight - preview.clientHeight)
  }

  function exportMarkdown() {
    const blob = new Blob([document], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = window.document.createElement('a')
    anchor.href = url
    anchor.download = `${plan.title || '计划'}.md`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  const saveLabel = {
    saved: '已保存',
    dirty: '未保存更改',
    saving: '保存中…',
    error: '保存失败',
  }[saveState]

  return (
    <div className="mx-auto flex h-[calc(100dvh-6rem)] w-full max-w-[1600px] flex-col">
      {/* 头部：返回 / 标题 / 保存态 / 操作 */}
      <div className="flex items-center gap-3">
        <Link to="/plan" className="text-sm text-muted-foreground hover:text-foreground">
          ← 计划
        </Link>
        <h1 className="truncate font-heading text-lg font-semibold">{plan.title}</h1>
        <span
          className={cn(
            'text-xs',
            saveState === 'error' ? 'text-destructive' : 'text-muted-foreground',
          )}
        >
          {saveLabel}
        </span>
        {stale && (
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[11px] text-primary">文档已变 · 可重拆任务</span>
        )}
        <div className="ml-auto flex items-center gap-2">
          <Button size="sm" variant="ghost" onClick={() => setFindOpen((open) => !open)}>
            查找
          </Button>
          <Button size="sm" variant="ghost" onClick={exportMarkdown}>
            导出 .md
          </Button>
          <Button size="sm" variant="outline" onClick={() => setDrawerOpen((open) => !open)}>
            任务（{tasks.filter((t) => t.status === 'DONE').length}/{tasks.length}）
          </Button>
        </div>
      </div>

      {actionError && <p className="mt-2 text-xs text-destructive">{actionError}</p>}

      {/* 查找栏 */}
      {findOpen && (
        <div className="mt-3 flex items-center gap-2 rounded-xl bg-white/5 px-3 py-2">
          <input
            autoFocus
            value={findQuery}
            onChange={(e) => setFindQuery(e.target.value)}
            placeholder="在预览中查找…"
            className="min-w-0 flex-1 bg-transparent text-sm outline-none"
          />
          <span className="text-xs tabular-nums text-muted-foreground">
            {findQuery.trim() ? `${findHits}${findHits >= FIND_HIT_LIMIT ? '+' : ''} 处` : ''}
          </span>
          <button
            className="text-xs text-muted-foreground hover:text-foreground"
            onClick={() => {
              setFindOpen(false)
              setFindQuery('')
            }}
          >
            Esc 关闭
          </button>
        </div>
      )}

      {/* 三面板主区 */}
      <div className="mt-4 flex min-h-0 flex-1 gap-4">
        <OutlinePanel document={document} onJumpToLine={scrollToLine} onReorder={onDocumentChange} />

        <div className="flex min-w-0 flex-1 gap-4">
          <textarea
            ref={editorRef}
            value={document}
            onChange={(e) => onDocumentChange(e.target.value)}
            onSelect={(e) => {
              const text = (e.target as HTMLTextAreaElement).value
              const start = (e.target as HTMLTextAreaElement).selectionStart
              const end = (e.target as HTMLTextAreaElement).selectionEnd
              setSelection(start !== end ? text.slice(start, end) : null)
            }}
            onScroll={syncPreviewScroll}
            spellCheck={false}
            className="glass-panel h-full min-w-0 flex-1 resize-none rounded-2xl p-5 font-mono text-sm leading-relaxed outline-none"
            placeholder="用 Markdown 写计划…「# 标题」会进入大纲"
          />
          <div
            ref={previewRef}
            className="glass-panel hidden h-full min-w-0 flex-1 overflow-y-auto rounded-2xl p-5 lg:block"
          >
            <MarkdownRenderer text={document} />
          </div>
        </div>

        <AIPanel
          planId={plan.id}
          selection={selection}
          onClearSelection={() => {
            setSelection(null)
            editorRef.current?.setSelectionRange(0, 0)
          }}
        />
      </div>

      {/* 任务抽屉（右缘覆盖） */}
      {drawerOpen && (
        <div className="fixed inset-y-0 right-0 z-50 flex items-center pr-4">
          <TaskDrawer
            planId={plan.id}
            tasks={tasks}
            stale={stale}
            onTasksChanged={(next) => {
              setTasks(next)
              // 拆分后指纹已更新，stale 复位（服务端 stale 为准，这里乐观同步）
              setStale(false)
            }}
            onClose={() => setDrawerOpen(false)}
          />
        </div>
      )}
    </div>
  )
}
