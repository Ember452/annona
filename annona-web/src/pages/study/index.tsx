import { useCallback, useState } from 'react'

import CheckinCard from '@/components/study/CheckinCard'
import PomodoroStation from '@/components/study/PomodoroStation'
import SessionList from '@/components/study/SessionList'
import StatsPanel from '@/components/study/StatsPanel'
import IslandCard from '@/components/island/IslandCard'
import { usePresence } from '@/hooks/usePresence'

/** 自习室（P1a-04 采集 + P2-01/02 足迹）：番茄钟 + 打卡卡 + 今日会话 + 小岛 + 年度统计。 */
export default function StudyPage() {
  // 会话落定计数：finish 成功后 bump，驱动今日列表重新拉取（minutes/quality 由服务端判定）
  const [settledCount, setSettledCount] = useState(0)
  const handleSessionSettled = useCallback(() => setSettledCount((count) => count + 1), [])
  const online = usePresence()

  return (
    <section className="mx-auto w-full max-w-5xl">
      <div className="flex items-center justify-between gap-4">
        <h1 className="font-heading text-2xl font-semibold tracking-tight">自习室</h1>
        {online !== null && (
          <span
            className="flex items-center gap-1.5 rounded-full border border-border bg-white/5 px-3 py-1 text-xs text-muted-foreground"
            title="匿名共学：只显示在线人数，不显示任何人"
          >
            <span className="size-1.5 animate-pulse rounded-full bg-emerald-400" />
            {online} 人正在共学
          </span>
        )}
      </div>
      <p className="mt-2 text-sm text-muted-foreground">
        番茄钟与每日打卡。专注时长由服务端按心跳判定质量——挂机如实降级，不替你圆谎。
      </p>

      <div className="mt-8 grid items-start gap-6 lg:grid-cols-[1.1fr_1fr]">
        <PomodoroStation onSessionSettled={handleSessionSettled} />
        <CheckinCard onSessionsChanged={handleSessionSettled} />
      </div>

      <div className="mt-6">
        <SessionList refreshKey={settledCount} />
      </div>

      <div className="mt-6">
        <IslandCard />
      </div>

      <div className="mt-6">
        <StatsPanel />
      </div>
    </section>
  )
}
