import { useEffect, useState } from 'react'

import { studyApi } from '@/api/study'

/** 轮询间隔：后端在场窗口 45s（容忍 4 次丢包），前端 10s 一拍。 */
const POLL_MS = 10_000

/**
 * 匿名共学在线数（P2-05）：10s 轮询 GET /api/study/presence（轮询即心跳，服务端
 * 顺带刷新自己在场）。共学是装饰态——失败静默保持旧值，后台标签页暂停轮询，
 * 回到前台后下一个周期自然恢复。
 */
export function usePresence(): number | null {
  const [online, setOnline] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    async function poll() {
      if (document.hidden) return
      try {
        const status = await studyApi.presence()
        if (!cancelled) setOnline(status.online)
      } catch {
        // 静默：徽章保持最后一次成功值
      }
    }
    void poll()
    const timer = setInterval(() => void poll(), POLL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [])

  return online
}
