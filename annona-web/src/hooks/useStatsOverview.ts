import { useCallback, useEffect, useState } from 'react'

import { studyApi } from '@/api/study'
import { toErrorMessage } from '@/lib/errors'
import { shanghaiToday } from '@/lib/statsView'
import type { StatsOverview } from '@/types/study'

/**
 * 年度统计总览拉取（P2-01/P2-02 共用）：StatsPanel（热力图）与 IslandCard（小岛）
 * 各自挂载本 hook，不再各写一套 fetch/loading/error。
 * 年份固定「今天（Asia/Shanghai）所在年」；跨年切换留待真实需要。
 */
export function useStatsOverview() {
  const [overview, setOverview] = useState<StatsOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      const data = await studyApi.statsOverview(Number(shanghaiToday().slice(0, 4)))
      setOverview(data)
      setError(null)
    } catch (e) {
      setError(toErrorMessage(e, '统计加载失败'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  return { overview, error, loading, refresh }
}
