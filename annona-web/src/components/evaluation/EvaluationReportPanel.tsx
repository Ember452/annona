import { useCallback, useEffect, useRef, useState } from 'react'

import { ApiError } from '@/api/request'
import { evaluationApi } from '@/api/evaluation'
import { RadarChart } from '@/components/evaluation/RadarChart'
import { formatScore, shouldPoll } from '@/lib/evaluationView'
import type { EvaluationReport } from '@/types/evaluation'

const POLL_INTERVAL_MS = 3000
// 报告行由交卷事件异步建（AFTER_COMMIT）+ 消费异步评分；给足轮询窗口（约 2 分钟）后停，
// 提示刷新——评估是尽力而为的后置链路，不做无限轮询。
const MAX_ATTEMPTS = 40

/**
 * 评估报告面板（P1b-06/07 前端 T4）：交卷后轮询 GET report，按 status 呈现。
 * PENDING/RUNNING → "评估进行中"；FAILED → 展示失败原因；DONE → 综合分 + 逐题雷达图 +
 * 整场汇总 + 逐题明细（降级题显式标注、不显示假分）。IO/轮询在本组件，展示逻辑走纯函数 lib。
 */
export function EvaluationReportPanel({ sessionId }: { sessionId: string }) {
  const [report, setReport] = useState<EvaluationReport | null>(null)
  const attempts = useRef(0)
  const timer = useRef<ReturnType<typeof setInterval> | null>(null)

  const stop = useCallback(() => {
    if (timer.current !== null) {
      clearInterval(timer.current)
      timer.current = null
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      attempts.current += 1
      try {
        const r = await evaluationApi.report(sessionId)
        if (cancelled) return
        setReport(r)
        if (!shouldPoll(r.status) || attempts.current >= MAX_ATTEMPTS) stop()
      } catch (e) {
        if (cancelled) return
        // 3000 = 报告尚未生成（事件/消费的短暂滞后）：继续轮询直到出分或超窗
        if (!(e instanceof ApiError) || e.code !== 3000) {
          stop()
          setReport({
            sessionId, status: 'FAILED', evaluatorVersion: '', compositeScore: null,
            summary: null, questions: [], chatModel: null, evaluatorModel: null,
            promptHash: null, error: e instanceof ApiError ? e.message : '报告加载失败',
            generatedAt: '',
          })
        } else if (attempts.current >= MAX_ATTEMPTS) {
          stop()
        }
      }
    }
    void load()
    timer.current = setInterval(() => void load(), POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      stop()
    }
  }, [sessionId, stop])

  if (report === null) {
    return <p className="text-sm text-muted-foreground" data-testid="iv-eval-pending">评估进行中…</p>
  }
  if (report.status === 'PENDING' || report.status === 'RUNNING') {
    return <p className="text-sm text-muted-foreground" data-testid="iv-eval-pending">评估进行中…</p>
  }
  if (report.status === 'FAILED') {
    return (
      <p className="text-sm text-destructive" data-testid="iv-eval-failed">
        评估失败：{report.error ?? '未知错误'}
      </p>
    )
  }

  const degradedCount = report.questions.filter((q) => q.fallbackUsed).length
  return (
    <div className="space-y-4" data-testid="iv-eval-report">
      <div className="flex items-baseline gap-2">
        <span className="font-heading text-3xl font-semibold">{formatScore(report)}</span>
        <span className="text-sm text-muted-foreground">综合分（难度加权，0-100）</span>
      </div>

      {report.summary && (
        <div className="space-y-1 text-sm">
          {report.summary.overall && <p className="text-muted-foreground">{report.summary.overall}</p>}
          {report.summary.strengths.length > 0 && (
            <p><span className="font-medium">亮点：</span>{report.summary.strengths.join('；')}</p>
          )}
          {report.summary.improvements.length > 0 && (
            <p><span className="font-medium">改进：</span>{report.summary.improvements.join('；')}</p>
          )}
        </div>
      )}

      <div className="flex justify-center text-primary">
        <RadarChart questions={report.questions} />
      </div>

      <ul className="space-y-3">
        {report.questions.map((q) => (
          <li key={`${q.questionId}:${q.followUpIndex}`} className="rounded-md border p-3 text-sm">
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">
                {q.followUpIndex === 0 ? '主问题' : `追问 ${q.followUpIndex}`}
              </span>
              {q.fallbackUsed
                ? <span className="text-xs text-amber-600">评估降级（保留原文待复核）</span>
                : <span className="font-medium">{q.score ?? '—'} 分</span>}
            </div>
            {q.feedback && <p className="mt-1">{q.feedback}</p>}
          </li>
        ))}
      </ul>
      {degradedCount > 0 && (
        <p className="text-xs text-muted-foreground">注：{degradedCount} 题评估降级，未计入综合分。</p>
      )}
    </div>
  )
}
