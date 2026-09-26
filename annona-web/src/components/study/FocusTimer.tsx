import { cn } from '@/lib/utils'

const RING_RADIUS = 88
const CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS
const SVG_SIZE = 220
const SVG_CENTER = SVG_SIZE / 2

interface FocusTimerProps {
  mode: 'focus' | 'break'
  round: number
  completed: number
  remaining: number
  totalSeconds: number
}

/**
 * 倒计时环（借鉴 🅢 focus-timer 的 SVG 环形进度：dashOffset 表达进度、tabular-nums 防抖动），
 * 视觉适配 annona token（stroke-primary/muted），不引 motion 库——纯 CSS transition。
 * 纯展示组件：计时状态全部来自 usePomodoro。
 */
export default function FocusTimer({ mode, round, completed, remaining, totalSeconds }: FocusTimerProps) {
  const progress = totalSeconds > 0 ? 1 - remaining / totalSeconds : 0
  const dashOffset = CIRCUMFERENCE * (1 - progress)
  const displayMin = Math.floor(remaining / 60)
  const displaySec = remaining % 60
  const timeDisplay = `${String(displayMin).padStart(2, '0')}:${String(displaySec).padStart(2, '0')}`

  return (
    <div className="flex flex-col items-center">
      <div className="relative">
        <svg
          width={SVG_SIZE}
          height={SVG_SIZE}
          viewBox={`0 0 ${SVG_SIZE} ${SVG_SIZE}`}
          className="-rotate-90"
        >
          <circle
            cx={SVG_CENTER}
            cy={SVG_CENTER}
            r={RING_RADIUS}
            fill="none"
            strokeWidth={6}
            className="stroke-muted"
          />
          <circle
            cx={SVG_CENTER}
            cy={SVG_CENTER}
            r={RING_RADIUS}
            fill="none"
            strokeWidth={6}
            strokeLinecap="round"
            strokeDasharray={CIRCUMFERENCE}
            strokeDashoffset={dashOffset}
            className={cn(
              'transition-[stroke-dashoffset] duration-500 ease-linear',
              mode === 'focus' ? 'stroke-primary' : 'stroke-emerald-500'
            )}
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-xs font-medium tracking-[0.15em] text-muted-foreground uppercase">
            第 {round} 轮 · {mode === 'focus' ? '专注' : '休息'}
          </span>
          <span className="mt-1 text-5xl font-light tabular-nums tracking-tight text-foreground">
            {timeDisplay}
          </span>
        </div>
      </div>
      {completed > 0 && (
        <div className="mt-5 flex items-center gap-1.5" aria-label={`今日完成 ${completed} 个番茄钟`}>
          {Array.from({ length: Math.min(completed, 8) }).map((_, i) => (
            <span key={i} className="h-2 w-2 rounded-full bg-primary/70" />
          ))}
          {completed > 8 && <span className="ml-1 text-xs text-muted-foreground">×{completed}</span>}
        </div>
      )}
    </div>
  )
}
