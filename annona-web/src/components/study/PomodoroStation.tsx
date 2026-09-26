import { useState } from 'react'

import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import DirectionSelector from '@/components/direction/DirectionSelector'
import FocusTimer from '@/components/study/FocusTimer'
import { usePomodoro } from '@/hooks/usePomodoro'
import { cn } from '@/lib/utils'
import type { Direction } from '@/types/direction'

const FOCUS_PRESETS = [25, 45, 60]
const BREAK_MINUTES = 5

interface PomodoroStationProps {
  /** 一次会话落定（自然到期 / 主动放弃的 finish 成功）后通知父级刷新今日列表。 */
  onSessionSettled: () => void
}

/**
 * 番茄钟工作站（借鉴 🅢 pomodoro-station 的 25/45/60 预设 + focus/break 轮转）：
 * 开始前必选方向（session 直接挂 direction_id，禁止自由文本）；预设切换在会话进行中禁用
 * （plannedMinutes 已随 start 落库）；放弃用简单 confirm（上游 long-press-exit 属 P2 沉浸
 * 模式场景，已扫描、判定不适配）。
 */
export default function PomodoroStation({ onSessionSettled }: PomodoroStationProps) {
  const [direction, setDirection] = useState<Direction | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const pomodoro = usePomodoro({ onSessionSettled })

  async function handleStart() {
    if (!direction) return
    setActionError(null)
    try {
      await pomodoro.start(direction.id)
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '开始失败，请稍后重试')
    }
  }

  function requestAbandon() {
    if (!window.confirm('确定提前结束本次专注？已专注时长仍会按心跳如实判定质量。')) return
    setActionError(null)
    pomodoro.abandon().catch((e: unknown) => {
      setActionError(e instanceof Error ? e.message : '结束失败，请稍后重试')
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>番茄钟</CardTitle>
        <CardDescription>
          开始前先选方向。专注结束或提前结束时，由服务端按心跳时间线判定质量——中途挂机会如实降级。
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col items-center gap-6">
        <div className="w-full max-w-md">
          <DirectionSelector value={direction} onChange={setDirection} />
        </div>

        <div
          className="flex items-center rounded-lg border border-input p-1"
          role="group"
          aria-label="专注时长预设"
        >
          {FOCUS_PRESETS.map((minutes) => (
            <button
              key={minutes}
              type="button"
              disabled={pomodoro.hasSession}
              onClick={() => pomodoro.setFocusMinutes(minutes)}
              className={cn(
                'rounded-md px-3 py-1.5 text-xs font-medium transition-colors disabled:opacity-50',
                pomodoro.focusMinutes === minutes
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              )}
            >
              {minutes} 分钟
            </button>
          ))}
        </div>

        <FocusTimer
          mode={pomodoro.mode}
          round={pomodoro.round}
          completed={pomodoro.completed}
          remaining={pomodoro.remaining}
          totalSeconds={(pomodoro.mode === 'focus' ? pomodoro.focusMinutes : BREAK_MINUTES) * 60}
        />

        <div className="flex flex-wrap items-center justify-center gap-3">
          {!pomodoro.hasSession && pomodoro.mode === 'focus' && (
            <Button
              size="lg"
              disabled={!direction || pomodoro.starting}
              onClick={() => void handleStart()}
            >
              {pomodoro.starting ? '启动中…' : '开始专注'}
            </Button>
          )}
          {pomodoro.hasSession && (
            <Button
              size="lg"
              variant="outline"
              onClick={() => (pomodoro.isRunning ? pomodoro.pause() : pomodoro.resume())}
            >
              {pomodoro.isRunning ? '暂停' : '继续'}
            </Button>
          )}
          {pomodoro.hasSession && (
            <Button size="lg" variant="destructive" disabled={pomodoro.abandoning} onClick={requestAbandon}>
              {pomodoro.abandoning ? '结束中…' : '提前结束'}
            </Button>
          )}
          {pomodoro.mode === 'break' && !pomodoro.hasSession && (
            <Button size="lg" variant="outline" onClick={pomodoro.skipBreak}>
              跳过休息
            </Button>
          )}
          {!pomodoro.hasSession && (pomodoro.completed > 0 || pomodoro.round > 1) && (
            <Button size="lg" variant="ghost" onClick={pomodoro.reset}>
              重置
            </Button>
          )}
        </div>

        {actionError && <p className="text-xs text-destructive">{actionError}</p>}
      </CardContent>
    </Card>
  )
}
