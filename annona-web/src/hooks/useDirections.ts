import { useCallback, useEffect, useState } from 'react'
import { directionApi } from '../api/direction'
import type { CreateDirectionInput, Direction } from '../types/direction'

/**
 * direction 字典的取数与变更（P1a-03）。
 *
 * <p>变更成功后本地 refresh 全量列表（数据量 ≤ 200 + 内置，单查询足够）；
 * 不引 react-query、不轮询。`error` 只承载列表加载失败；create/archive/bindKbDoc
 * 的失败直接 reject（Error.message 已由 request 拦截器转成可读文案），由调用侧就地展示。
 * refresh 仅内部使用，不对外暴露（YAGNI）。
 */
export function useDirections() {
  const [directions, setDirections] = useState<Direction[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      setDirections(await directionApi.list())
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : '方向列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

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

  return { directions, loading, error, create, archive, bindKbDoc }
}
