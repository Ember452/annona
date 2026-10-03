import { useState } from 'react'

import { planApi } from '@/api/plan'
import { Button } from '@/components/ui/button'
import { toErrorMessage } from '@/lib/errors'
import { cn } from '@/lib/utils'
import type { PlanTask, PlanTaskStatus } from '@/types/plan'

interface TaskDrawerProps {
  planId: string
  tasks: PlanTask[]
  stale: boolean
  onTasksChanged: (tasks: PlanTask[]) => void
  onClose: () => void
}

/**
 * 任务抽屉（P2-06）：任务清单 + 勾选 + 手动追加 + AI 拆任务。
 * 进度条 = progressMinutes/targetMinutes（联动累计，后端维护）；stale 徽章提示文档已变可重拆。
 */
export default function TaskDrawer({ planId, tasks, stale, onTasksChanged, onClose }: TaskDrawerProps) {
  const [splitting, setSplitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [newTitle, setNewTitle] = useState('')

  async function runSplit() {
    setSplitting(true)
    setError(null)
    try {
      const response = await planApi.split(planId)
      onTasksChanged(response.tasks)
    } catch (e) {
      setError(toErrorMessage(e, '拆分失败，请确认已配置模型'))
    } finally {
      setSplitting(false)
    }
  }

  async function toggle(task: PlanTask) {
    const next: PlanTaskStatus = task.status === 'DONE' ? 'PENDING' : 'DONE'
    try {
      const updated = await planApi.patchTask(planId, task.id, next)
      onTasksChanged(tasks.map((t) => (t.id === updated.id ? updated : t)))
    } catch (e) {
      setError(toErrorMessage(e, '状态更新失败'))
    }
  }

  async function addTask() {
    const title = newTitle.trim()
    if (!title) return
    try {
      const created = await planApi.addTask(planId, { title })
      onTasksChanged([...tasks, created])
      setNewTitle('')
    } catch (e) {
      setError(toErrorMessage(e, '追加失败'))
    }
  }

  const doneCount = tasks.filter((t) => t.status === 'DONE').length

  return (
    <aside className="glass-panel-strong flex w-80 shrink-0 flex-col gap-4 rounded-2xl p-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold">
          任务清单（{doneCount}/{tasks.length}）
        </h2>
        <button className="text-xs text-muted-foreground hover:text-foreground" onClick={onClose}>
          收起
        </button>
      </div>

      <Button size="sm" disabled={splitting} onClick={() => void runSplit()}>
        {splitting ? '拆分中…（可能十几秒）' : stale ? 'AI 拆任务 · 文档已变' : '重新 AI 拆任务'}
      </Button>

      <div className="flex flex-col gap-2 overflow-y-auto">
        {tasks.length === 0 && (
          <p className="py-6 text-center text-xs text-muted-foreground">
            还没有任务——写好计划后点上方按钮让 AI 拆解。
          </p>
        )}
        {tasks.map((task) => (
          <div key={task.id} className="rounded-xl bg-white/5 p-3">
            <div className="flex items-start gap-2">
              <input
                type="checkbox"
                className="mt-0.5 size-4 accent-[var(--theme-primary)]"
                checked={task.status === 'DONE'}
                onChange={() => void toggle(task)}
                aria-label={`勾选任务：${task.title}`}
              />
              <div className="min-w-0 flex-1">
                <p className={cn('text-xs font-medium', task.status === 'DONE' && 'text-muted-foreground line-through')}>
                  {task.title}
                </p>
                {task.description && (
                  <p className="mt-1 line-clamp-2 text-[11px] text-muted-foreground">{task.description}</p>
                )}
                <div className="mt-2 flex items-center gap-2">
                  <div className="h-1 flex-1 overflow-hidden rounded-full bg-white/10">
                    <div
                      className="h-full rounded-full bg-primary/70"
                      style={{ width: `${Math.min(100, (task.progressMinutes / task.targetMinutes) * 100)}%` }}
                    />
                  </div>
                  <span className="text-[10px] tabular-nums text-muted-foreground">
                    {task.progressMinutes}/{task.targetMinutes}min
                  </span>
                  <span className="rounded bg-white/10 px-1 text-[10px] text-muted-foreground">{task.priority}</span>
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className="flex gap-2">
        <input
          value={newTitle}
          onChange={(e) => setNewTitle(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && void addTask()}
          placeholder="手动追加任务…"
          className="min-w-0 flex-1 rounded-lg border border-input bg-transparent px-2 py-1.5 text-xs outline-none focus:ring-1 focus:ring-ring"
        />
        <Button size="sm" variant="outline" onClick={() => void addTask()}>
          追加
        </Button>
      </div>

      {error && <p className="text-[11px] text-destructive">{error}</p>}
    </aside>
  )
}
