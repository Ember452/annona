import { useCallback, useEffect, useState } from 'react'

import { ApiError } from '@/api/request'
import { decisionApi } from '@/api/decisions'
import { applyRejection, emptyHint, isRejected, ruleLabel } from '@/lib/decisionView'
import type { DecisionTrace } from '@/types/decision'

/**
 * 可解释决策面板（P1c-06）：读某场面试的 decision_trace，把规则链/guard 的留痕逐条呈现，
 * 每条带"这条不对"反驳按钮（P1c-07 降权，影响后续组卷）。IO 在本组件，展示逻辑在 lib 纯函数。
 *
 * <p>诚实呈现：无留痕（默认策略出题/降级）显示空态提示，不编造理由；已驳回的条目按钮禁用。
 * 驳回失败（3200/3201）时必须可见并**回到服务端真相**——不能把乐观态当成已落库。
 */
export function ExplainPanel({ sessionId }: { sessionId: string }) {
  const [traces, setTraces] = useState<DecisionTrace[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setTraces(await decisionApi.sessionTraces(sessionId))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '决策记录加载失败')
    }
  }, [sessionId])

  useEffect(() => {
    void load()
  }, [load])

  const onReject = useCallback(async (traceId: string) => {
    // 乐观更新先标驳回；失败则展示错误并从服务端重拉（并发抢驳时本地已标态是假的）
    setTraces((prev) => (prev ? applyRejection(prev, traceId) : prev))
    try {
      await decisionApi.reject(traceId)
    } catch (e) {
      // 先重拉服务端真相再落本次失败原因：load 成功时会清掉旧 error，顺序反了刚写的错误会被抹掉
      await load()
      setError(e instanceof ApiError ? e.message : '驳回失败，请稍后重试')
    }
  }, [load])

  if (traces === null) {
    return error
      ? <p className="text-sm text-destructive" data-testid="dc-error">{error}</p>
      : <p className="text-sm text-muted-foreground" data-testid="dc-loading">决策记录加载中…</p>
  }

  const hint = emptyHint(traces)
  if (hint) {
    return <p className="text-sm text-muted-foreground" data-testid="dc-empty">{hint}</p>
  }

  return (
    <>
      {error && (
        <p className="mb-2 text-sm text-destructive" data-testid="dc-error">{error}</p>
      )}
      <ul className="space-y-2" data-testid="dc-panel">
        {traces.map((t) => (
          <li key={t.traceId} className="rounded-md border p-3 text-sm">
            <div className="flex items-center justify-between">
              <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs">
                {ruleLabel(t.ruleKey)}
              </span>
              {isRejected(t) ? (
                <span className="text-xs text-muted-foreground" data-testid="dc-rejected">已驳回</span>
              ) : (
                <button
                  type="button"
                  className="text-xs text-muted-foreground underline-offset-2 hover:underline"
                  onClick={() => void onReject(t.traceId)}
                  data-testid={`dc-reject-${t.traceId}`}
                >
                  这条不对
                </button>
              )}
            </div>
            <p className="mt-1 text-foreground">{t.reason}</p>
          </li>
        ))}
      </ul>
    </>
  )
}
