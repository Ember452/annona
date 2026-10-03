import { useEffect, useState } from 'react'

import FocusTimer from '@/components/study/FocusTimer'
import LongPressExit from '@/components/study/LongPressExit'
import { useAmbientSound } from '@/hooks/useAmbientSound'
import { randomQuote } from '@/lib/quotes'
import { useScene } from '@/stores/scene'

interface ImmersiveOverlayProps {
  /** focus | break（usePomodoro 的轮转模式）。 */
  mode: 'focus' | 'break'
  round: number
  completed: number
  /** 剩余秒数。 */
  remaining: number
  totalSeconds: number
  onExit: () => void
}

/**
 * 沉浸覆盖层（P2-03，借 🅢 rainforest-focus-room 的全屏形态）：fixed inset-0、
 * 只有计时器 + 一句鼓励语 + 长按退出 + 环境音开关。鼓励语进入时取一句不重复的；
 * 切走标签页时环境音自动暂停、BLUR 事件照常上报（useHeartbeat/useAmbientSound 各自
 * 的 visibilitychange 语义），这里无需重复处理。
 */
export default function ImmersiveOverlay({
  mode,
  round,
  completed,
  remaining,
  totalSeconds,
  onExit,
}: ImmersiveOverlayProps) {
  const { scene } = useScene()
  // 鼓励语只进层时取一次（上游同口径：不随计时轮换，避免打扰）
  const [quote] = useState(() => randomQuote(scene))
  const { volume, setVolume } = useAmbientSound(scene)

  // 挂到 body 上锁滚动；卸载（含路由切换）必须还原
  useEffect(() => {
    document.body.classList.add('annona-immersive')
    return () => document.body.classList.remove('annona-immersive')
  }, [])

  const soundOn = volume > 0

  return (
    <div className="fixed inset-0 z-[60] flex select-none flex-col items-center justify-center bg-background px-6">
      <FocusTimer mode={mode} round={round} completed={completed} remaining={remaining} totalSeconds={totalSeconds} />
      <p className="mt-10 max-w-md cursor-default text-center text-sm font-light leading-relaxed tracking-wide text-primary/55">
        「{quote}」
      </p>
      <button
        type="button"
        aria-label={soundOn ? '关闭环境音' : '开启环境音'}
        onClick={() => setVolume(soundOn ? 0 : 0.3)}
        className="fixed right-6 top-6 rounded-full border border-border px-3 py-1.5 text-xs text-muted-foreground backdrop-blur-sm"
      >
        {soundOn ? '🔊 环境音开' : '🔇 环境音关'}
      </button>
      <LongPressExit onExit={onExit} />
    </div>
  )
}
