import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import PlanStudio from '@/components/plan/PlanStudio'
import { planApi } from '@/api/plan'
import { toErrorMessage } from '@/lib/errors'
import type { PlanDetail } from '@/types/plan'

/** 工作室路由页：装载计划详情后进三面板编辑器。 */
export default function PlanStudioPage() {
  const { planId } = useParams<{ planId: string }>()
  const [plan, setPlan] = useState<PlanDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!planId) return
    let cancelled = false
    planApi.detail(planId)
      .then((detail) => {
        if (!cancelled) setPlan(detail)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(toErrorMessage(e, '计划加载失败'))
      })
    return () => {
      cancelled = true
    }
  }, [planId])

  if (error) {
    return (
      <section className="mx-auto w-full max-w-5xl py-20 text-center">
        <p className="text-sm text-muted-foreground">{error}</p>
        <Link to="/plan" className="mt-4 inline-block text-sm text-primary hover:underline">← 回计划列表</Link>
      </section>
    )
  }
  if (!plan) {
    return (
      <section className="mx-auto w-full max-w-5xl py-20 text-center text-sm text-muted-foreground">
        正在打开工作室…
      </section>
    )
  }
  return <PlanStudio key={plan.id} plan={plan} />
}
