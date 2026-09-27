import { useCallback, useState } from 'react'

import CheckinCard from '@/components/study/CheckinCard'
import PomodoroStation from '@/components/study/PomodoroStation'
import SessionList from '@/components/study/SessionList'

/** 自习室（P1a-04）：番茄钟 + 打卡卡 + 今日会话三区。 */
export default function StudyPage() {
  // 会话落定计数：finish 成功后 bump，驱动今日列表重新拉取（minutes/quality 由服务端判定）
  const [settledCount, setSettledCount] = useState(0)
  const handleSessionSettled = useCallback(() => setSettledCount((count) => count + 1), [])

  return (
    <section className="mx-auto w-full max-w-5xl">
      <h1 className="font-heading text-2xl font-semibold tracking-tight">自习室</h1>
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
    </section>
  )
}
