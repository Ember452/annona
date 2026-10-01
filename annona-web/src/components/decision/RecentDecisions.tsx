import { useEffect, useState } from 'react'

import { ApiError } from '@/api/request'
import { decisionApi } from '@/api/decisions'
import { groupBySession, ruleLabel } from '@/lib/decisionView'
import type { DecisionTrace } from '@/types/decision'

// 一场面试通常 1–4 条留痕；取 20 条足够覆盖最近 5 场（分组后再截 5 场）。
const FETCH_LIMIT = 20
const SESSION_LIMIT = 5

/**
 * 首页"最近 5 场决策"摘要（P1c-06 出口①）：读最近决策留痕，按会话分组展示每场的理由，
 * 非项目成员也能读懂"凭什么这么考"。空态诚实提示。
 */
export function RecentDecisions() {
  const [traces, setTraces] = useState<DecisionTrace[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    decisionApi
      .recent(FETCH_LIMIT)
      .then((t) => { if (!cancelled) setTraces(t) })
      .catch((e) => { if (!cancelled) setError(e instanceof ApiError ? e.message : '决策记录加载失败') })
    return () => { cancelled = true }
  }, [])

  if (error) {
    return <p className="text-sm text-destructive" data-testid="dc-recent-error">{error}</p>
  }
  if (traces === null) {
    return <p className="text-sm text-muted-foreground" data-testid="dc-recent-loading">加载中…</p>
  }
  const groups = groupBySession(traces).slice(0, SESSION_LIMIT)
  if (groups.length === 0) {
    return <p className="text-sm text-muted-foreground" data-testid="dc-recent-empty">还没有决策记录，先开始一场面试吧。</p>
  }

  return (
    <div className="space-y-3" data-testid="dc-recent">
      {groups.map((g) => (
        <div key={g.sessionId} className="rounded-md border p-3">
          {g.traces.map((t) => (
            <div key={t.traceId} className="flex items-start gap-2 text-sm">
              <span className="mt-0.5 inline-flex shrink-0 items-center rounded-full bg-muted px-2 py-0.5 text-xs">
                {ruleLabel(t.ruleKey)}
              </span>
              <span className="text-muted-foreground">{t.reason}</span>
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}
