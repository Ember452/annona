import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { planApi } from '@/api/plan'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useDirections } from '@/hooks/useDirections'
import { toErrorMessage } from '@/lib/errors'
import { PLAN_TEMPLATES } from '@/lib/studio/planTemplates'
import { cn } from '@/lib/utils'
import type { PlanDetail, PlanSummary, PlanTask } from '@/types/plan'

/**
 * 计划日程（P2-06）：计划列表 + 模板库新建 + 今日待办（跨计划 PENDING 任务）。
 * 面试日程解析与突击入口仍属 P4——本页 v1 只做学习计划。
 */
export default function PlanPage() {
  const navigate = useNavigate()
  const [plans, setPlans] = useState<PlanSummary[]>([])
  const [todos, setTodos] = useState<PlanTask[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const [planList, todayTasks] = await Promise.all([planApi.list(), planApi.today()])
      setPlans(planList)
      setTodos(todayTasks)
      setError(null)
    } catch (e) {
      setError(toErrorMessage(e, '计划加载失败'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  async function remove(plan: PlanSummary) {
    if (!window.confirm(`删除计划「${plan.title}」？其任务一并删除。`)) return
    try {
      await planApi.remove(plan.id)
      setPlans((prev) => prev.filter((p) => p.id !== plan.id))
    } catch (e) {
      setError(toErrorMessage(e, '删除失败'))
    }
  }

  return (
    <section className="mx-auto w-full max-w-5xl">
      <div className="flex items-center justify-between gap-4">
        <h1 className="font-heading text-2xl font-semibold tracking-tight">计划日程</h1>
        <Button onClick={() => setCreating(true)}>新建计划</Button>
      </div>
      <p className="mt-2 text-sm text-muted-foreground">
        一份 MD 计划 → AI 拆成任务 → 打卡的时长按方向自动回写任务进度。
      </p>

      {error && <p className="mt-4 text-sm text-destructive">{error}</p>}

      {loading ? (
        <Card className="mt-6">
          <CardContent className="py-10 text-center text-sm text-muted-foreground">加载中…</CardContent>
        </Card>
      ) : (
        <>
          <div className="mt-6 grid gap-4 sm:grid-cols-2">
            {plans.length === 0 && !creating && (
              <Card className="sm:col-span-2">
                <CardContent className="py-10 text-center text-sm text-muted-foreground">
                  还没有计划——点右上角「新建计划」从模板开始。
                </CardContent>
              </Card>
            )}
            {plans.map((plan) => {
              const percent = plan.totalTasks === 0 ? 0 : Math.round((plan.doneTasks / plan.totalTasks) * 100)
              return (
                <Card key={plan.id} className="transition-transform hover:-translate-y-0.5">
                  <CardHeader>
                    <CardTitle className="flex items-center justify-between gap-2">
                      <button className="truncate text-left hover:text-primary" onClick={() => navigate(`/plan/${plan.id}`)}>
                        {plan.title}
                      </button>
                      <button
                        className="shrink-0 text-xs font-normal text-muted-foreground hover:text-destructive"
                        onClick={() => void remove(plan)}
                        aria-label={`删除计划：${plan.title}`}
                      >
                        删除
                      </button>
                    </CardTitle>
                    <CardDescription>
                      任务 {plan.doneTasks}/{plan.totalTasks} 完成
                      {percent > 0 && ` · ${percent}%`}
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/10">
                      <div className="h-full rounded-full bg-primary/70" style={{ width: `${percent}%` }} />
                    </div>
                    <div className="mt-3 flex justify-end">
                      <Button size="sm" variant="outline" onClick={() => navigate(`/plan/${plan.id}`)}>
                        打开工作室
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              )
            })}
          </div>

          {creating && (
            <NewPlanDialog
              onClose={() => setCreating(false)}
              onCreated={(plan) => {
                setCreating(false)
                navigate(`/plan/${plan.id}`)
              }}
            />
          )}

          <div className="mt-8">
            <Card>
              <CardHeader>
                <CardTitle>今日待办</CardTitle>
                <CardDescription>全部计划中未完成的任务（优先级高在前）；打卡后按方向自动累计进度。</CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                {todos.length === 0 && (
                  <p className="py-4 text-center text-sm text-muted-foreground">今日待办清空了，去自习室打卡赚进度。</p>
                )}
                {todos.map((task) => (
                  <div key={task.id} className="flex items-center justify-between rounded-xl bg-white/5 px-3 py-2">
                    <div className="min-w-0">
                      <p className="truncate text-sm">{task.title}</p>
                      <p className="text-[11px] text-muted-foreground">
                        {task.progressMinutes}/{task.targetMinutes} 分钟
                        {task.priority === 'high' && ' · 高优先'}
                      </p>
                    </div>
                    <span className="shrink-0 rounded bg-white/10 px-1.5 py-0.5 text-[10px] text-muted-foreground">
                      {task.source === 'AI' ? 'AI' : '手动'}
                    </span>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>
        </>
      )}
    </section>
  )
}

function NewPlanDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (plan: PlanDetail) => void }) {
  const { directions } = useDirections()
  const [title, setTitle] = useState('')
  const [directionId, setDirectionId] = useState<string>('')
  const [templateKey, setTemplateKey] = useState(PLAN_TEMPLATES[0].key)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit() {
    const trimmed = title.trim()
    if (!trimmed) return
    setSubmitting(true)
    setError(null)
    try {
      const template = PLAN_TEMPLATES.find((t) => t.key === templateKey) ?? PLAN_TEMPLATES[0]
      const plan = await planApi.create({
        title: trimmed,
        directionId: directionId || null,
        document: template.document,
      })
      onCreated(plan)
    } catch (e) {
      setError(toErrorMessage(e, '创建失败'))
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <Card className="w-full max-w-lg" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <CardTitle>新建计划</CardTitle>
          <CardDescription>选一个模板起步，进入工作室后随时改写。</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <input
            autoFocus
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void submit()}
            placeholder="计划标题（如：Java 并发 30 天）"
            className="w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus:ring-1 focus:ring-ring"
          />

          <select
            value={directionId}
            onChange={(e) => setDirectionId(e.target.value)}
            className="w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus:ring-1 focus:ring-ring"
          >
            <option value="">归属方向（可选，打卡联动按方向匹配）</option>
            {directions.map((direction) => (
              <option key={direction.id} value={direction.id}>{direction.name}</option>
            ))}
          </select>

          <div className="grid grid-cols-3 gap-2">
            {PLAN_TEMPLATES.map((template) => (
              <button
                key={template.key}
                onClick={() => setTemplateKey(template.key)}
                className={cn(
                  'rounded-xl border p-3 text-left transition-colors',
                  templateKey === template.key
                    ? 'border-primary bg-primary/10'
                    : 'border-border hover:border-primary/40',
                )}
              >
                <p className="text-xs font-medium">{template.label}</p>
                <p className="mt-1 text-[10px] leading-snug text-muted-foreground">{template.description}</p>
              </button>
            ))}
          </div>

          {error && <p className="text-xs text-destructive">{error}</p>}

          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={onClose}>取消</Button>
            <Button disabled={submitting || !title.trim()} onClick={() => void submit()}>
              {submitting ? '创建中…' : '创建'}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
