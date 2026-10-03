import { useState } from 'react'

import { extractOutline, swapLines, type OutlineEntry } from '@/lib/studio/outline'
import { cn } from '@/lib/utils'

interface OutlinePanelProps {
  document: string
  /** 点击大纲项：预览区滚动到对应标题。 */
  onJumpToLine: (line: number) => void
  /** 拖拽重排落点：父级拿新文档串走防抖保存。 */
  onReorder: (nextDocument: string) => void
}

/** 大纲缩进（每级 14px，上游同款节奏）。 */
function indent(level: number): number {
  return 12 + (level - 1) * 14
}

/**
 * 大纲面板（P2-06 三面板之一，借 🅢 outline-panel）：逐行提取标题，点击跳预览，
 * 拖拽>90px 触发两行互换（上游同款阈值——小幅误触不重排）。
 */
export default function OutlinePanel({ document: doc, onJumpToLine, onReorder }: OutlinePanelProps) {
  const entries = extractOutline(doc)
  const [dragging, setDragging] = useState<number | null>(null)
  const [dragStart, setDragStart] = useState<{ x: number; y: number } | null>(null)

  function handleDragStart(entry: OutlineEntry, e: React.DragEvent) {
    setDragging(entry.line)
    setDragStart({ x: e.clientX, y: e.clientY })
  }

  function handleDrop(target: OutlineEntry, e: React.DragEvent) {
    e.preventDefault()
    if (dragging === null || dragging === target.line || !dragStart) {
      reset()
      return
    }
    // >90px 才换位（上游阈值）：小位移当误触
    const moved = Math.hypot(e.clientX - dragStart.x, e.clientY - dragStart.y)
    if (moved > 90) {
      onReorder(swapLines(doc, dragging, target.line))
    }
    reset()
  }

  function reset() {
    setDragging(null)
    setDragStart(null)
  }

  return (
    <aside className="glass-panel-strong hidden w-52 shrink-0 flex-col overflow-y-auto rounded-2xl p-3 xl:flex">
      <h2 className="mb-2 px-1 text-xs font-semibold text-muted-foreground">大纲</h2>
      {entries.length === 0 && (
        <p className="px-1 py-4 text-[11px] leading-relaxed text-muted-foreground">
          用「# 标题」组织计划，大纲会出现在这里。
        </p>
      )}
      <div className="flex flex-col gap-0.5">
        {entries.map((entry) => (
          <button
            key={`${entry.line}-${entry.text}`}
            draggable
            onDragStart={(e) => handleDragStart(entry, e)}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => handleDrop(entry, e)}
            onDragEnd={reset}
            onClick={() => onJumpToLine(entry.line)}
            style={{ paddingLeft: indent(entry.level) }}
            className={cn(
              'truncate rounded-md py-1 pr-2 text-left text-[11px] text-muted-foreground transition-colors hover:bg-white/5 hover:text-foreground',
              dragging === entry.line && 'opacity-40',
            )}
            title={entry.text}
          >
            {entry.text}
          </button>
        ))}
      </div>
      {entries.length > 1 && (
        <p className="mt-3 px-1 text-[10px] text-muted-foreground">拖动标题超过 90px 可交换章节位置</p>
      )}
    </aside>
  )
}
