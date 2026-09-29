import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { API_BASE_URL } from '@/api/request'
import { questionbankApi } from '@/api/questionbank'
import DirectionSelector from '@/components/direction/DirectionSelector'
import InterviewCenter from '@/pages/interview/InterviewCenter'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  followUpGapWarning,
  strictCapacityMessage,
  usableFollowUpCount,
} from '@/lib/questionGap'
import type { Direction } from '@/types/direction'
import type {
  CapacityResponse,
  QbGenStatusResponse,
  QbProgressEvent,
  QbQuestion,
  QbQuestionStatus,
} from '@/types/questionbank'

/** 在途状态（SSE/轮询的停止条件取其补集）。 */
const GEN_IN_FLIGHT = ['QUEUED', 'PROCESSING']

const STATUS_LABELS: Record<QbQuestionStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '已启用',
  ARCHIVED: '已归档',
}

/** 出题进度的终态判定（QUEUED/PROCESSING 之外的都停）。 */
function isTerminal(status: string): boolean {
  return !GEN_IN_FLIGHT.includes(status)
}

export default function InterviewPage() {
  const [direction, setDirection] = useState<Direction | null>(null)
  const directionId = direction?.id ?? null

  // ===== 出题 =====
  const [difficulty, setDifficulty] = useState(3)
  const [questionCount, setQuestionCount] = useState(10)
  const [followUpCount, setFollowUpCount] = useState(2)
  const [genStatus, setGenStatus] = useState<QbGenStatusResponse | null>(null)
  const [progress, setProgress] = useState<QbProgressEvent | null>(null)
  const [generating, setGenerating] = useState(false)
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null)

  // ===== 题库 =====
  const [statusFilter, setStatusFilter] = useState<QbQuestionStatus | ''>('')
  const [questions, setQuestions] = useState<QbQuestion[]>([])
  const [listLoading, setListLoading] = useState(false)
  const [listError, setListError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  // ===== 容量 =====
  const [capacityDifficulty, setCapacityDifficulty] = useState(3)
  const [mainQuestionCount, setMainQuestionCount] = useState(3)
  const [capacity, setCapacity] = useState<CapacityResponse | null>(null)

  const loadQuestions = useCallback(async () => {
    if (!directionId) return
    setListLoading(true)
    setListError(null)
    try {
      setQuestions(
        await questionbankApi.list(directionId, {
          status: statusFilter === '' ? undefined : statusFilter,
        }),
      )
    } catch {
      setListError('题目列表加载失败，请稍后重试')
    } finally {
      setListLoading(false)
    }
  }, [directionId, statusFilter])

  useEffect(() => {
    void loadQuestions()
  }, [loadQuestions])

  // 容量：方向/难度/主问题数任一变化即刷新（借 🅖：前端实时禁用不可选档位）
  useEffect(() => {
    if (!directionId) return
    let cancelled = false
    questionbankApi
      .capacity(directionId, capacityDifficulty, mainQuestionCount)
      .then((c) => {
        if (!cancelled) setCapacity(c)
      })
      .catch(() => {
        if (!cancelled) setCapacity(null)
      })
    return () => {
      cancelled = true
    }
  }, [directionId, capacityDifficulty, mainQuestionCount])

  const stopPolling = useCallback(() => {
    if (pollTimer.current) {
      clearInterval(pollTimer.current)
      pollTimer.current = null
    }
  }, [])

  const onGenTerminal = useCallback(
    (status: string, message: string) => {
      setGenerating(false)
      void loadQuestions()
      void questionbankApi
        .generationStatus(directionId ?? '')
        .then((s) => s && setGenStatus(s))
        .catch(() => undefined)
      if (status === 'FAILED') setActionError(message || '出题失败')
    },
    [directionId, loadQuestions],
  )

  // 出题进度：SSE 为主，onerror 降级轮询（knowledge 页同款分工）
  useEffect(() => {
    if (!directionId || !generating) return
    const source = new EventSource(
      `${API_BASE_URL}/api/questionbank/directions/${directionId}/questions/progress`,
    )
    source.addEventListener('progress', (ev) => {
      const data = JSON.parse((ev as MessageEvent).data) as QbProgressEvent
      setProgress(data)
      if (isTerminal(data.status)) {
        source.close()
        onGenTerminal(data.status, data.message)
      }
    })
    source.onerror = () => {
      source.close()
      stopPolling()
      pollTimer.current = setInterval(async () => {
        try {
          const status = await questionbankApi.generationStatus(directionId)
          if (status && isTerminal(status.status)) {
            setProgress({
              status: status.status,
              stage: '',
              processed: status.savedCount,
              total: status.questionCount,
              message: status.message || status.error,
            })
            onGenTerminal(status.status, status.message || status.error)
          }
        } catch {
          // 轮询失败保留下一轮
        }
      }, 3000)
    }
    return () => {
      source.close()
      stopPolling()
    }
  }, [directionId, generating, onGenTerminal, stopPolling])

  async function handleGenerate() {
    if (!directionId || generating) return
    setGenerating(true)
    setActionError(null)
    setProgress({
      status: 'QUEUED',
      stage: '任务已提交',
      processed: 0,
      total: questionCount,
      message: '',
    })
    try {
      const status = await questionbankApi.generate(directionId, {
        difficulty,
        questionCount,
        followUpCount,
      })
      setGenStatus(status)
    } catch {
      setGenerating(false)
      setProgress(null)
      setActionError('出题任务提交失败，请稍后重试')
    }
  }

  async function handleStatusChange(question: QbQuestion, next: QbQuestionStatus) {
    if (!directionId) return
    setActionError(null)
    try {
      await questionbankApi.changeStatus(directionId, question.id, next)
      void loadQuestions()
    } catch {
      setActionError('状态变更失败')
    }
  }

  async function handleDelete(question: QbQuestion) {
    if (!directionId) return
    setActionError(null)
    try {
      await questionbankApi.remove(directionId, question.id)
      void loadQuestions()
    } catch {
      setActionError('删除失败')
    }
  }

  const capacityHint = useMemo(
    () =>
      capacity
        ? strictCapacityMessage(capacity.followUpOptions, mainQuestionCount, followUpCount)
        : null,
    [capacity, mainQuestionCount, followUpCount],
  )

  return (
    <section className="mx-auto w-full max-w-5xl space-y-6">
      <div>
        <h1 className="font-heading text-2xl font-semibold tracking-tight">模拟面试 · 题库</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          选择方向，从其绑定的知识库讲义生成带评分标准的题目；组卷与逐题作答在 P1b 后续批次落地。
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">方向与出题</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <DirectionSelector value={direction} onChange={setDirection} />

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div className="space-y-1.5">
              <Label htmlFor="qb-difficulty">难度档位</Label>
              <select
                id="qb-difficulty"
                className="w-full rounded-md border bg-background px-3 py-2 text-sm"
                value={difficulty}
                onChange={(e) => setDifficulty(Number(e.target.value))}
              >
                {[1, 2, 3, 4, 5].map((d) => (
                  <option key={d} value={d}>
                    {d} 档
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="qb-count">目标题数</Label>
              <Input
                id="qb-count"
                type="number"
                min={1}
                max={30}
                value={questionCount}
                onChange={(e) => setQuestionCount(Number(e.target.value))}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="qb-followups">每题追问目标</Label>
              <select
                id="qb-followups"
                className="w-full rounded-md border bg-background px-3 py-2 text-sm"
                value={followUpCount}
                onChange={(e) => setFollowUpCount(Number(e.target.value))}
              >
                {[0, 1, 2, 3, 4, 5].map((f) => (
                  <option key={f} value={f}>
                    {f} 个
                  </option>
                ))}
              </select>
            </div>
          </div>

          <Button onClick={() => void handleGenerate()} disabled={!directionId || generating}>
            {generating ? '出题中…' : '开始出题'}
          </Button>

          {progress && (
            <p className="text-sm text-muted-foreground" data-testid="gen-progress">
              {progress.stage || progress.status}
              {progress.total > 0 ? `（${progress.processed}/${progress.total}）` : ''}
              {progress.message ? ` · ${progress.message}` : ''}
            </p>
          )}
          {actionError && <p className="text-sm text-destructive">{actionError}</p>}
        </CardContent>
      </Card>

      <InterviewCenter directionId={directionId} />

      <Card>
        <CardHeader>
          <CardTitle className="text-base">开考容量校验</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="cap-difficulty">难度档位</Label>
              <select
                id="cap-difficulty"
                className="w-full rounded-md border bg-background px-3 py-2 text-sm"
                value={capacityDifficulty}
                onChange={(e) => setCapacityDifficulty(Number(e.target.value))}
              >
                {[1, 2, 3, 4, 5].map((d) => (
                  <option key={d} value={d}>
                    {d} 档
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="cap-main">主问题数</Label>
              <Input
                id="cap-main"
                type="number"
                min={1}
                max={20}
                value={mainQuestionCount}
                onChange={(e) => setMainQuestionCount(Number(e.target.value))}
              />
            </div>
          </div>
          {capacity && (
            <div className="space-y-2" data-testid="capacity-options">
              <div className="flex flex-wrap gap-2">
                {capacity.followUpOptions.map((o) => (
                  <label
                    key={o.followUpCount}
                    className={`rounded-md border px-3 py-1.5 text-sm ${
                      o.selectable
                        ? 'cursor-pointer border-primary bg-primary/5'
                        : 'cursor-not-allowed opacity-40'
                    }`}
                    title={o.selectable ? '' : `仅 ${o.availableQuestionCount} 道题可用`}
                  >
                    <input
                      type="radio"
                      name="followup-tier"
                      className="mr-1.5"
                      disabled={!o.selectable}
                      checked={false}
                      readOnly
                    />
                    每题 {o.followUpCount} 个追问（{o.availableQuestionCount} 道可用）
                  </label>
                ))}
              </div>
              {capacityHint && <p className="text-sm text-destructive">{capacityHint}</p>}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">题目列表</CardTitle>
          <select
            aria-label="状态筛选"
            className="rounded-md border bg-background px-2 py-1.5 text-sm"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as QbQuestionStatus | '')}
          >
            <option value="">全部状态</option>
            <option value="DRAFT">草稿</option>
            <option value="ACTIVE">已启用</option>
            <option value="ARCHIVED">已归档</option>
          </select>
        </CardHeader>
        <CardContent className="space-y-3">
          {listError && <p className="text-sm text-destructive">{listError}</p>}
          {listLoading && <p className="text-sm text-muted-foreground">加载中…</p>}
          {!listLoading && questions.length === 0 && (
            <p className="text-sm text-muted-foreground">
              {directionId ? '该方向暂无题目——先在上方发起出题' : '先选择一个方向'}
            </p>
          )}
          {questions.map((q) => {
            const gap =
              genStatus && followUpGapWarning(usableFollowUpCount(q.followUps), genStatus.followUpCount)
            return (
              <div key={q.id} className="rounded-md border p-3" data-testid="question-card">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-medium">{q.question}</p>
                    {q.topicSummary && (
                      <p className="mt-0.5 text-xs text-muted-foreground">{q.topicSummary}</p>
                    )}
                  </div>
                  <div className="flex shrink-0 items-center gap-2 text-xs">
                    <span className="rounded bg-secondary px-1.5 py-0.5">难度 {q.difficulty}</span>
                    <span className="rounded bg-secondary px-1.5 py-0.5">
                      {STATUS_LABELS[q.status]}
                    </span>
                  </div>
                </div>
                {q.scoringRubric && (
                  <p className="mt-2 text-xs text-muted-foreground">评分标准：{q.scoringRubric}</p>
                )}
                {gap && <p className="mt-1 text-xs text-amber-600">{gap}</p>}
                <div className="mt-2 flex gap-2 text-xs">
                  {q.status === 'DRAFT' && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => void handleStatusChange(q, 'ACTIVE')}
                    >
                      启用
                    </Button>
                  )}
                  {q.status === 'ACTIVE' && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => void handleStatusChange(q, 'DRAFT')}
                    >
                      撤回
                    </Button>
                  )}
                  {q.status !== 'ARCHIVED' && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => void handleStatusChange(q, 'ARCHIVED')}
                    >
                      归档
                    </Button>
                  )}
                  <Button variant="ghost" size="sm" onClick={() => void handleDelete(q)}>
                    删除
                  </Button>
                </div>
              </div>
            )
          })}
        </CardContent>
      </Card>
    </section>
  )
}
