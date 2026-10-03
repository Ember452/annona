import { useRef, useState } from 'react'

/** 与 globals.css 里 .immersive-hold 动画时长（1.4s）保持一致，两处必须同步改。 */
export const IMMERSIVE_HOLD_MS = 1400

/**
 * 长按退出沉浸层（借 🅢 long-press-exit）：指针按住 1400ms 触发退出，
 * 提前松开/移出/打断即取消——防误触比确认弹窗更适全屏无干扰场景。
 */
export default function LongPressExit({ onExit }: { onExit: () => void }) {
  const [holding, setHolding] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  function cancel() {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }

  function start() {
    setHolding(true)
    cancel()
    timerRef.current = setTimeout(() => {
      timerRef.current = null
      onExit()
    }, IMMERSIVE_HOLD_MS)
  }

  function end() {
    setHolding(false)
    cancel()
  }

  return (
    <button
      type="button"
      className={`immersive-exit${holding ? ' is-holding' : ''}`}
      onPointerDown={start}
      onPointerUp={end}
      onPointerLeave={end}
      onPointerCancel={end}
    >
      <span className="relative z-10">长按退出沉浸</span>
    </button>
  )
}
