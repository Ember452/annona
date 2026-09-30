import { useCallback, useEffect, useReducer, useState } from 'react'

import { ApiError } from '@/api/request'
import { interviewApi } from '@/api/interview'
import { questionbankApi } from '@/api/questionbank'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { strictCapacityMessage } from '@/lib/questionGap'
import {
  flowReducer,
  initialFlow,
  slotKey,
  slotsByOrder,
} from '@/lib/interviewFlow'
import type { SessionSummary } from '@/types/interview'
import type { SlotView } from '@/types/interview'

const textareaClass =
  'w-full min-h-24 rounded-md border border-input bg-transparent px-2.5 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50'

/**
 * 面试中心（P1b-04/05 前端）：开始面试 → 逐题作答（含追问槽）→ 交卷；
 * 断线重进自动恢复到当前题（服务端 currentIndex 为准）。状态迁移全部走
 * `lib/interviewFlow` 纯 reducer（vitest 覆盖），组件只做编排与 IO。
 * 评分报告属批 3——交卷后如实呈现落库事实，不做假评分。
 */
export default function InterviewCenter({ directionId }: { directionId: string | null }) {
  const [flow, dispatch] = useReducer(flowReducer, initialFlow)
  const [totalCount, setTotalCount] = useState(3)
  const [difficulty, setDifficulty] = useState(3)
  const [followUpDepth, setFollowUpDepth] = useState(1)
  const [capacityHint, setCapacityHint] = useState<string | null>(null)
  const [resumable, setResumable] = useState<SessionSummary[]>([])

  useEffect(() => {
    if (!directionId) {
      setResumable([])
      return
    }
    let cancelled = false
    interviewApi.listResumable(directionId)
      .then((list) => {
        if (!cancelled) setResumable(list)
      })
      .catch(() => undefined)   // 恢复列表拉不到不阻塞开新面试（进页时再兜底）
    return () => {
      cancelled = true
    }
  }, [directionId, flow.phase])

  // 容量预检复用批 1 capacity 端点（2604 才是最终裁判，这里只是提前告知）
  useEffect(() => {
    if (!directionId || flow.phase !== 'form') return
    let cancelled = false
    questionbankApi.capacity(directionId, difficulty, totalCount)
      .then((c) => {
        if (!cancelled) {
          setCapacityHint(strictCapacityMessage(c.followUpOptions, totalCount, followUpDepth))
        }
      })
      .catch(() => {
        if (!cancelled) setCapacityHint(null)
      })
    return () => {
      cancelled = true
    }
  }, [directionId, difficulty, totalCount, followUpDepth, flow.phase])

  const start = useCallback(async () => {
    if (!directionId) return
    dispatch({ type: 'BUSY', busy: true })
    try {
      const view = await interviewApi.create({
        directionId,
        totalCount,
        difficulties: Array.from({ length: totalCount }, () => difficulty),
        followUpDepth,
      })
      dispatch({ type: 'OPEN', view })
    } catch (e) {
      dispatch({ type: 'FAIL', message: e instanceof ApiError ? e.message : '开始面试失败，请稍后重试' })
    }
  }, [directionId, totalCount, difficulty, followUpDepth])

  const resume = useCallback(async (sessionId: string) => {
    try {
      dispatch({ type: 'OPEN', view: await interviewApi.get(sessionId) })
    } catch (e) {
      dispatch({ type: 'FAIL', message: e instanceof ApiError ? e.message : '恢复会话失败' })
    }
  }, [])

  /** 把指定槽位组的非空草稿逐槽落库（失败即抛，由调用方的 FAIL 路径显示，草稿保留）。 */
  const saveDrafts = useCallback(async (viewId: string, groups: SlotView[][]) => {
    for (const group of groups) {
      for (const slot of group) {
        const text = flow.drafts[slotKey(slot)]
        if (text != null && text.trim() !== '' && !slot.answered) {
          await interviewApi.answer(viewId, slot.questionId, slot.followUpIndex, text)
        }
      }
    }
  }, [flow.drafts])

  /** 保存并下一题：只提交当前 order 的槽位组。 */
  const saveAndAdvance = useCallback(async () => {
    if (!flow.view) return
    dispatch({ type: 'BUSY', busy: true })
    try {
      await saveDrafts(flow.view.id, [slotsByOrder(flow.view)[flow.order] ?? []])
      const last = flow.view.totalCount - 1
      if (flow.order < last) {
        dispatch({ type: 'ADVANCE' })
      } else {
        dispatch({ type: 'BUSY', busy: false })
      }
    } catch (e) {
      dispatch({ type: 'FAIL', message: e instanceof ApiError ? e.message : '作答保存失败，请重试' })
    }
  }, [flow.view, flow.order, saveDrafts])

  const submitAll = useCallback(async () => {
    if (!flow.view) return
    dispatch({ type: 'BUSY', busy: true })
    try {
      await saveDrafts(flow.view.id, slotsByOrder(flow.view))
      const done = await interviewApi.finalize(flow.view.id)
      dispatch({ type: 'SUBMITTED', view: { ...flow.view, status: 'COMPLETED',
        answeredCount: done.answeredCount } })
    } catch (e) {
      dispatch({ type: 'FAIL', message: e instanceof ApiError ? e.message : '交卷失败，请重试' })
    }
  }, [flow.view, saveDrafts])

  const groups = slotsByOrder(flow.view)
  const currentGroup = groups[flow.order] ?? []
  const isLastOrder = flow.view != null && flow.order >= flow.view.totalCount - 1

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">面试中心</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {flow.phase === 'form' && (
          <>
            {resumable.length > 0 && (
              <div className="space-y-2 rounded-md border bg-muted/30 p-3" data-testid="resume-list">
                <p className="text-sm font-medium">有未完成的面试</p>
                {resumable.map((s) => (
                  <div key={s.id} className="flex items-center justify-between gap-2 text-sm">
                    <span>进行到第 {s.currentIndex + 1}/{s.totalCount} 题</span>
                    <span className="flex gap-2">
                      <Button size="sm" onClick={() => void resume(s.id)}>继续</Button>
                      <Button size="sm" variant="outline"
                        onClick={() => void interviewApi.abandon(s.id)
                          .then(() => setResumable((r) => r.filter((x) => x.id !== s.id)))}>
                        放弃
                      </Button>
                    </span>
                  </div>
                ))}
              </div>
            )}
            <div className="grid grid-cols-3 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="iv-count">主问题数</Label>
                <Input id="iv-count" type="number" min={1} max={20} value={totalCount}
                  onChange={(e) => setTotalCount(Math.min(20, Math.max(1, Number(e.target.value) || 1)))} />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="iv-difficulty">难度档位</Label>
                <select id="iv-difficulty" className="w-full rounded-md border bg-background px-3 py-2 text-sm"
                  value={difficulty} onChange={(e) => setDifficulty(Number(e.target.value))}>
                  {[1, 2, 3, 4, 5].map((d) => <option key={d} value={d}>{d} 档</option>)}
                </select>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="iv-depth">追问层数</Label>
                <select id="iv-depth" className="w-full rounded-md border bg-background px-3 py-2 text-sm"
                  value={followUpDepth} onChange={(e) => setFollowUpDepth(Number(e.target.value))}>
                  {[0, 1, 2, 3].map((f) => <option key={f} value={f}>{f} 层</option>)}
                </select>
              </div>
            </div>
            {capacityHint && (
              <p className="text-sm text-muted-foreground" data-testid="iv-capacity-hint">{capacityHint}</p>
            )}
            <Button onClick={() => void start()} disabled={!directionId || flow.busy}>
              {directionId ? '开始面试' : '请先选择方向'}
            </Button>
            {flow.error && <p className="text-sm text-destructive">{flow.error}</p>}
          </>
        )}

        {flow.phase === 'answering' && flow.view && (
          <div className="space-y-4" data-testid="iv-answer-panel">
            <p className="text-sm text-muted-foreground">
              第 {flow.order + 1}/{flow.view.totalCount} 题
              {flow.view.skippedReasons.length > 0 && (
                <span className="text-amber-600"> · {flow.view.skippedReasons.join('；')}</span>
              )}
            </p>
            {currentGroup.map((slot) => (
              <div key={slotKey(slot)} className="space-y-1.5">
                <Label className={slot.followUpIndex === 0 ? 'text-sm font-medium' : 'text-sm text-muted-foreground'}>
                  {slot.followUpIndex === 0 ? slot.questionText
                    : `追问 ${slot.followUpIndex}：${slot.questionText}`}
                  {slot.answered && <span className="ml-2 text-xs text-green-600">已作答</span>}
                </Label>
                <textarea
                  className={textareaClass}
                  defaultValue={flow.drafts[slotKey(slot)] ?? slot.answerText ?? ''}
                  disabled={slot.answered}
                  onChange={(e) => dispatch({ type: 'DRAFT', slotKey: slotKey(slot), text: e.target.value })}
                />
              </div>
            ))}
            <div className="flex gap-2">
              {!isLastOrder && (
                <Button onClick={() => void saveAndAdvance()} disabled={flow.busy}>
                  保存并下一题
                </Button>
              )}
              <Button variant={isLastOrder ? 'default' : 'outline'}
                onClick={() => void submitAll()} disabled={flow.busy}
                data-testid="iv-finalize">
                交卷
              </Button>
              <Button variant="ghost" onClick={() => dispatch({ type: 'RESET' })}>退出</Button>
            </div>
            {flow.error && <p className="text-sm text-destructive">{flow.error}</p>}
          </div>
        )}

        {flow.phase === 'submitted' && flow.view && (
          <div className="space-y-3" data-testid="iv-submitted">
            <p className="text-sm">
              已交卷：{flow.view.answeredCount} 个作答槽已落库（本场共 {flow.view.totalCount} 题）。
            </p>
            <p className="text-sm text-muted-foreground">
              评分与报告将在下一批开放（评估器版本 v1，交卷幂等已生效）。
            </p>
            <Button variant="outline" onClick={() => dispatch({ type: 'RESET' })}>返回面试中心</Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
