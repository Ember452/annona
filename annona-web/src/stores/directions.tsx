import { createContext, useCallback, useEffect, useState } from 'react'

import { directionApi } from '@/api/direction'
import { useAuth } from '@/stores/auth'
import type { CreateDirectionInput, Direction } from '@/types/direction'
import { toErrorMessage } from '@/lib/errors'

/**
 * direction 字典的共享状态（P1a-03 引入 per-hook 实例；登录 UI 批改为全局单份）。
 *
 * <p>此前每个组件实例各拉一次列表：自习室页三处挂载 = 3 次相同请求，且 A 卡新建的
 * 方向不会出现在 B 卡的下拉里。现在收敛为 <DirectionsProvider> 单实例，本文件只保留
 * 消费 hook（签名与迁移前完全一致，调用侧零改动）。
 *
 * <p>取数时机跟登录态走：未登录（含登录前探测期）不请求——接口必然 1004；user 出现
 * 后拉取，登出/失效清空。变更成功后本地 refresh 全量列表（数据量 ≤ 200 + 内置，
 * 单查询足够）；不引 react-query、不轮询。`error` 只承载列表加载失败；create/archive/
 * bindKbDoc 的失败直接 reject（ApiError 已由 request 拦截器归一），由调用侧就地展示。
 */
interface DirectionsContextValue {
  directions: Direction[]
  loading: boolean
  error: string | null
  create(input: CreateDirectionInput): Promise<Direction>
  archive(id: string): Promise<void>
  bindKbDoc(id: string, kbDocId: string): Promise<Direction>
}

export const DirectionsContext = createContext<DirectionsContextValue | null>(null)

export function DirectionsProvider({ children }: { children: React.ReactNode }) {
  const { user } = useAuth()
  const [directions, setDirections] = useState<Direction[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      setDirections(await directionApi.list())
      setError(null)
    } catch (e) {
      setError(toErrorMessage(e, '方向列表加载失败'))
    } finally {
      setLoading(false)
    }
  }, [])

  const userId = user?.id ?? null
  useEffect(() => {
    // 登出/会话失效：清空数据并回到"未加载"态，等下一次登录重取；
    // 未登录不发请求（守卫保证挂载在 AppLayout 内的组件只见登录态，但
    // provider 本体挂在路由外，必须自己处理探测期）
    if (!userId) {
      setDirections([])
      setError(null)
      setLoading(true)
      return
    }
    void refresh()
  }, [userId, refresh])

  const create = useCallback(
    async (input: CreateDirectionInput) => {
      const created = await directionApi.create(input)
      await refresh()
      return created
    },
    [refresh],
  )

  const archive = useCallback(
    async (id: string) => {
      await directionApi.archive(id)
      await refresh()
    },
    [refresh],
  )

  const bindKbDoc = useCallback(
    async (id: string, kbDocId: string) => {
      const updated = await directionApi.bindKbDoc(id, { kbDocId })
      await refresh()
      return updated
    },
    [refresh],
  )

  return (
    <DirectionsContext.Provider value={{ directions, loading, error, create, archive, bindKbDoc }}>
      {children}
    </DirectionsContext.Provider>
  )
}
