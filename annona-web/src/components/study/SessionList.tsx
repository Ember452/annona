import { useCallback, useEffect, useState } from 'react'

import { studyApi } from '@/api/study'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useDirections } from '@/hooks/useDirections'
import { cn } from '@/lib/utils'
import type { SessionQuality, StudySession } from '@/types/study'
import { toErrorMessage } from '@/lib/errors'

/** 质量徽章三色（设计 §6.1：面板必须显示质量等级）。 */
const QUALITY_BADGE: Record<SessionQuality, { label: string; className: string }> = {
  VERIFIED: {
    label: '已验证',
    className:
      'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-300',
  },
  PARTIAL: {
    label: '部分验证',
    className:
      'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-300',
  },
  SELF_REPORTED: {
    label: '自报',
    className: 'border-border bg-muted text-muted-foreground',
  },
}

const MODE_LABEL: Record<StudySession['mode'], string> = {
  POMODORO: '番茄钟',
  IMMERSIVE: '沉浸',
  CHECKIN: '打卡/补录',
}

interface SessionListProps {
  /** 变化时重新拉取（会话落定后由父级 bump）。 */
  refreshKey: number
}

function formatClock(iso: string): string {
  return new Date(iso).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

/** 今日会话列表：进行中显示"进行中"，已结束显示 minutes + 质量徽章。 */
export default function SessionList({ refreshKey }: SessionListProps) {
  const [sessions, setSessions] = useState<StudySession[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const { directions } = useDirections()

  const refresh = useCallback(async () => {
    try {
      setSessions(await studyApi.todaySessions())
      setError(null)
    } catch (e) {
      setError(toErrorMessage(e, '今日会话加载失败'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh, refreshKey])

  return (
    <Card>
      <CardHeader>
        <CardTitle>今日会话</CardTitle>
        <CardDescription>
          质量由服务端按心跳时间线判定：已验证 = 连续在座，部分验证 = 中途长时间离席（时长按墙钟补上），
          自报 = 打卡联动或手动补录。
        </CardDescription>
      </CardHeader>
      <CardContent>
        {loading && <p className="text-sm text-muted-foreground">加载中…</p>}
        {error && <p className="text-sm text-destructive">{error}</p>}
        {!loading && !error && sessions.length === 0 && (
          <p className="text-sm text-muted-foreground">今天还没有会话，开一个番茄钟或补录一段吧。</p>
        )}
        {sessions.length > 0 && (
          <ul className="divide-y divide-border">
            {sessions.map((session) => {
              const directionName =
                directions.find((d) => d.id === session.directionId)?.name ?? '未知方向'
              const running = session.endAt === null
              const badge = session.quality ? QUALITY_BADGE[session.quality] : null
              return (
                <li key={session.id} className="flex items-center gap-3 py-3 text-sm">
                  <span className="w-28 shrink-0 tabular-nums text-muted-foreground">
                    {formatClock(session.startAt)}
                    {session.endAt === null ? ' 起' : `–${formatClock(session.endAt)}`}
                  </span>
                  <span className="w-28 shrink-0 truncate" title={directionName}>
                    {directionName}
                  </span>
                  <span className="w-20 shrink-0 text-muted-foreground">
                    {MODE_LABEL[session.mode]}
                  </span>
                  <span className="ml-auto shrink-0 tabular-nums">
                    {running ? '进行中' : `${session.minutes ?? 0} 分钟`}
                  </span>
                  {badge && (
                    <span
                      className={cn(
                        'shrink-0 rounded-full border px-2 py-0.5 text-xs',
                        badge.className
                      )}
                    >
                      {badge.label}
                    </span>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
