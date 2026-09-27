import { useContext } from 'react'

import { DirectionsContext } from '@/stores/directions'

/**
 * direction 字典的取数与变更（P1a-03）。
 *
 * <p>实现与状态在 <DirectionsProvider>（stores/directions）——全局单份，避免每组件
 * 实例重复请求与跨组件不同步（登录 UI 批收敛）。本 hook 只做消费；签名与迁移前一致，
 * DirectionSelector / CheckinCard / SessionList 零改动。
 */
export function useDirections() {
  const ctx = useContext(DirectionsContext)
  if (!ctx) {
    throw new Error('useDirections 必须在 <DirectionsProvider> 内使用')
  }
  return ctx
}
