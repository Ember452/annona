import { Suspense, lazy, useEffect, useState } from 'react'

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useStatsOverview } from '@/hooks/useStatsOverview'
import { computeStreak, computeTotals, shanghaiToday } from '@/lib/statsView'
import { islandAriaLabel, shouldUseIsland3D } from '@/lib/island'

// 3D 场景独立 chunk（three 系 ~600KB）：懒加载，触屏/窄视口用户根本不下载
const LazyIsland = lazy(() => import('@/components/island/LearningIsland'))

function useCoarsePointer(): boolean {
  const [coarse, setCoarse] = useState(
    () => typeof window !== 'undefined' && window.matchMedia('(pointer: coarse)').matches,
  )
  useEffect(() => {
    const query = window.matchMedia('(pointer: coarse)')
    const onChange = () => setCoarse(query.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])
  return coarse
}

/** 2D 降级剪影：主题色静态 SVG（层叠山体 + 主色树簇），数据口径与 3D 版共用。 */
function Island2D({ label }: { label: string }) {
  return (
    <div className="flex h-72 w-full items-center justify-center" role="img" aria-label={label}>
      <svg viewBox="0 0 320 180" className="h-full w-auto max-w-full">
        {/* 底座 */}
        <ellipse cx="160" cy="140" rx="130" ry="26" fill="color-mix(in oklab, var(--theme-primary) 12%, transparent)" />
        <ellipse cx="160" cy="132" rx="96" ry="20" fill="color-mix(in oklab, var(--theme-primary) 22%, transparent)" />
        {/* 山体 */}
        <polygon points="120,132 160,64 200,132" fill="color-mix(in oklab, var(--theme-primary) 40%, transparent)" />
        <polygon points="150,132 185,80 220,132" fill="color-mix(in oklab, var(--theme-primary) 55%, transparent)" />
        <polygon points="152,80 160,64 168,80 160,86" fill="color-mix(in oklab, var(--theme-primary) 90%, transparent)" />
        {/* 树簇 */}
        {[[104, 118], [92, 126], [228, 122], [214, 130], [176, 116]].map(([x, y], i) => (
          <circle key={i} cx={x} cy={y} r={9} fill="color-mix(in oklab, var(--theme-primary) 70%, transparent)" />
        ))}
      </svg>
    </div>
  )
}

/**
 * 学习小岛卡（P2-02）：3D 场景懒加载；触屏主设备或 <768px 视口走 2D 剪影降级
 * （帧率预算——移动端 30fps 的 WebGL 收益撑不起加载成本）。数据来自年度统计
 * （全量打卡数驱动解锁，年内数据出 streak/时长/方向）。
 */
export default function IslandCard() {
  const { overview, error, loading, refresh } = useStatsOverview()
  const coarse = useCoarsePointer()
  const [viewportWidth, setViewportWidth] = useState(
    () => (typeof window !== 'undefined' ? window.innerWidth : 1280),
  )
  useEffect(() => {
    const onResize = () => setViewportWidth(window.innerWidth)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  const use3D = shouldUseIsland3D({ coarsePointer: coarse, viewportWidth })

  const today = shanghaiToday()
  const stats = overview
    ? {
        totalCheckins: overview.totalCheckins,
        streak: computeStreak(overview.days, today),
        totalMinutes: computeTotals(overview.days).verifiedTotal + computeTotals(overview.days).selfTotal,
        todayCount: overview.days.some((d) => d.day === today && d.verifiedMinutes + d.selfReportedMinutes > 0) ? 1 : 0,
        directionIds: overview.directions.map((d) => d.directionId),
      }
    : null
  const label = stats
    ? islandAriaLabel({
        totalCheckins: stats.totalCheckins,
        streak: stats.streak,
        totalMinutes: stats.totalMinutes,
        directionCount: stats.directionIds.length,
      })
    : '学习小岛'

  return (
    <Card>
      <CardHeader>
        <CardTitle>学习小岛</CardTitle>
        <CardDescription>
          每次打卡都会让小岛长出新的格子；不同方向的格子长着不同的植物。
          {use3D ? '可拖动环视。' : '当前设备展示 2D 剪影。'}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {loading && (
          <div className="flex h-72 items-center justify-center text-sm text-muted-foreground">正在加载小岛…</div>
        )}
        {!loading && (error || !stats) && (
          <div className="flex h-72 flex-col items-center justify-center gap-3 text-sm text-muted-foreground">
            <span>{error ?? '小岛数据加载失败'}</span>
            <button className="text-primary underline-offset-2 hover:underline" onClick={() => void refresh()}>
              重试
            </button>
          </div>
        )}
        {!loading && stats && (
          <div className="h-72">
            {use3D ? (
              <Suspense fallback={<div className="h-full w-full animate-pulse rounded-xl bg-white/5" />}>
                <LazyIsland
                  totalCheckins={stats.totalCheckins}
                  streak={stats.streak}
                  totalMinutes={stats.totalMinutes}
                  todayCount={stats.todayCount}
                  directionIds={stats.directionIds}
                />
              </Suspense>
            ) : (
              <Island2D label={label} />
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
