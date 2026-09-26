import { useEffect } from 'react'

import { studyApi } from '@/api/study'

const HEARTBEAT_INTERVAL_MS = 15_000

/**
 * 会话心跳（P1a-04）：active（专注运行中）时每 15s 一跳证明"活着"。
 *
 * <p>失焦（`visibilitychange` hidden）暂停心跳并上报 BLUR——唯一允许前端上报的事件；
 * 服务端时间线把失焦段如实记为 gap（≤60s 容忍、更长降级）。恢复可见立即补一跳并续跳。
 * 心跳失败静默：缺跳会被服务端如实降级质量，不重试不弹错（重试反而伪造连续性）。
 */
export function useHeartbeat(sessionId: string | null, active: boolean): void {
  useEffect(() => {
    if (!sessionId || !active) return
    let timer: number | undefined

    const stopTicking = () => {
      if (timer !== undefined) {
        window.clearInterval(timer)
        timer = undefined
      }
    }

    const beat = () => {
      void studyApi.heartbeat(sessionId).catch(() => {})
    }

    const startTicking = () => {
      stopTicking()
      beat()
      timer = window.setInterval(beat, HEARTBEAT_INTERVAL_MS)
    }

    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        stopTicking()
        void studyApi.reportBlur(sessionId).catch(() => {})
      } else {
        startTicking()
      }
    }

    startTicking()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      stopTicking()
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [sessionId, active])
}
