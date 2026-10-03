import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useStatsOverview } from '@/hooks/useStatsOverview'
import {
  computeLongestStreak,
  computeStreak,
  computeTotals,
  HEAT_OPACITY,
  heatmapLayout,
  lastNDays,
  shanghaiToday,
  WEEKDAY_LABELS,
  type DayMinutes,
  type HeatCell,
} from '@/lib/statsView'

/** 分钟数 humanize：<60 显分钟，否则显一位小数的小时。 */
function fmtMinutes(minutes: number): string {
  if (minutes < 60) return `${minutes} 分钟`
  const hours = minutes / 60
  const rounded = Math.round(hours * 10) / 10
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)} 小时`
}

const WEEKDAY_CHAR = ['日', '一', '二', '三', '四', '五', '六'] as const

function weekdayChar(date: string): string {
  return WEEKDAY_CHAR[new Date(`${date}T00:00:00Z`).getUTCDay()]
}

/** 色阶格子底色：正常日吃主色，仅自报日吃第二 chart 色——质量图例的视觉分家。 */
function cellColor(level: number, selfOnly: boolean): string | undefined {
  if (level <= 0) return undefined
  const pct = HEAT_OPACITY[level - 1]
  const hue = selfOnly ? 'var(--theme-chart-2, #c8b45a)' : 'var(--theme-primary, #d7ef83)'
  return `color-mix(in oklab, ${hue} ${pct}%, transparent)`
}

function cellTitle(cell: HeatCell, data?: DayMinutes) {
  if (cell.outside || !data) return cell.date
  const parts = [
    data.verifiedMinutes > 0 ? `有效 ${data.verifiedMinutes} 分钟` : null,
    data.selfReportedMinutes > 0 ? `自报 ${data.selfReportedMinutes} 分钟` : null,
  ].filter(Boolean)
  return `${cell.date} · ${parts.length > 0 ? parts.join(' + ') : '无记录'}`
}

/**
 * 年度专注足迹（P2-01）：热力图 + 质量图例 + 汇总卡 + 近 7 日柱状 + 方向分布。
 * 口径与后端同源（有效=VERIFIED+PARTIAL，自报分色不混算）；连续/日均等派生值
 * 由 lib/statsView 纯函数计算。年份固定当前年（跨年拼接留待真实需要）。
 */
export default function StatsPanel() {
  const { overview, error, loading, refresh } = useStatsOverview()

  if (loading) {
    return (
      <Card>
        <CardContent className="flex items-center justify-center gap-2 py-10 text-sm text-muted-foreground">
          <span className="size-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          正在统计…
        </CardContent>
      </Card>
    )
  }

  if (error || !overview) {
    return (
      <Card>
        <CardContent className="flex flex-col items-center gap-3 py-10 text-sm text-muted-foreground">
          <span>{error ?? '统计加载失败'}</span>
          <button className="text-primary underline-offset-2 hover:underline" onClick={() => void refresh()}>
            重试
          </button>
        </CardContent>
      </Card>
    )
  }

  const today = shanghaiToday()
  const layout = heatmapLayout(overview.days, overview.year)
  const totals = computeTotals(overview.days)
  const streak = computeStreak(overview.days, today)
  const longest = computeLongestStreak(overview.days, overview.year)

  const recentDays = lastNDays(7, today)
  const byDay = new Map(overview.days.map((d) => [d.day, d]))
  const recentMax = Math.max(
    45,
    ...recentDays.map((date) => {
      const data = byDay.get(date)
      return data ? data.verifiedMinutes + data.selfReportedMinutes : 0
    }),
  )

  const maxDirectionMinutes = Math.max(
    1,
    ...overview.directions.map((d) => d.verifiedMinutes + d.selfReportedMinutes),
  )

  return (
    <Card>
      <CardHeader>
        <CardTitle>{overview.year} 专注足迹</CardTitle>
        <CardDescription>
          有效专注 = 心跳验证（含部分验证）分钟；浅金格 = 仅自报/打卡补录，如实分色不混算。
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        {/* 汇总卡 */}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Summary label="总专注" value={fmtMinutes(totals.verifiedTotal)} />
          <Summary label="日均（有记录日）" value={fmtMinutes(totals.dailyAverage)} />
          <Summary label="连续天数" value={`${streak} 天`} />
          <Summary label="最长连续" value={`${longest} 天`} />
        </div>

        {/* 热力图 */}
        <div className="flex flex-col gap-2">
          <div className="flex gap-[3px] overflow-x-auto pb-1">
            <div className="mr-1 flex shrink-0 flex-col gap-[3px] pt-[18px]">
              {WEEKDAY_LABELS.map((label, row) => (
                <div key={row} className="h-[13px] text-[10px] leading-[13px] text-muted-foreground">
                  {label}
                </div>
              ))}
            </div>
            {layout.columns.map((column, columnIndex) => (
              <div key={columnIndex} className="flex flex-col gap-[3px]">
                {column.map((cell) => (
                  <div
                    key={cell.date}
                    title={cellTitle(cell, byDay.get(cell.date))}
                    className={
                      cell.outside
                        ? 'h-[13px] w-[13px] rounded-[3px]'
                        : cell.level === 0
                          ? 'h-[13px] w-[13px] rounded-[3px] bg-white/5'
                          : 'h-[13px] w-[13px] rounded-[3px]'
                    }
                    style={{ backgroundColor: cellColor(cell.level, cell.selfOnly) }}
                  />
                ))}
              </div>
            ))}
          </div>
          {/* 图例 */}
          <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
            <span>少</span>
            {[0, 1, 2, 3, 4].map((level) => (
              <div
                key={level}
                className={level === 0 ? 'h-[11px] w-[11px] rounded-[2px] bg-white/5' : 'h-[11px] w-[11px] rounded-[2px]'}
                style={{ backgroundColor: cellColor(level, false) }}
              />
            ))}
            <span>多</span>
            <div
              className="h-[11px] w-[11px] rounded-[2px]"
              style={{ backgroundColor: cellColor(2, true) }}
            />
            <span>仅补录</span>
          </div>
        </div>

        <div className="grid gap-6 lg:grid-cols-2">
          {/* 近 7 日 */}
          <div>
            <h3 className="text-sm font-medium">近 7 日</h3>
            <div className="mt-3 flex h-28 items-end gap-2">
              {recentDays.map((date) => {
                const data = byDay.get(date)
                const minutes = data ? data.verifiedMinutes + data.selfReportedMinutes : 0
                const isToday = date === today
                return (
                  <div key={date} className="flex flex-1 flex-col items-center gap-1">
                    <div
                      title={`${date} · ${fmtMinutes(minutes)}`}
                      className="flex h-20 w-full items-end justify-center rounded-md bg-white/5"
                    >
                      <div
                        className={
                          isToday
                            ? 'w-full rounded-md bg-primary'
                            : 'w-full rounded-md bg-primary/45'
                        }
                        style={{ height: `${Math.max(4, (minutes / recentMax) * 100)}%` }}
                      />
                    </div>
                    <span className={isToday ? 'text-[11px] text-primary' : 'text-[11px] text-muted-foreground'}>
                      {weekdayChar(date)}
                    </span>
                  </div>
                )
              })}
            </div>
          </div>

          {/* 方向分布 */}
          <div>
            <h3 className="text-sm font-medium">方向分布</h3>
            {overview.directions.length === 0 ? (
              <p className="mt-3 text-sm text-muted-foreground">还没有方向专注记录。</p>
            ) : (
              <div className="mt-3 flex flex-col gap-2">
                {overview.directions.slice(0, 5).map((direction) => {
                  const minutes = direction.verifiedMinutes + direction.selfReportedMinutes
                  return (
                    <div key={direction.directionId} className="flex flex-col gap-1">
                      <div className="flex justify-between text-xs text-muted-foreground">
                        <span className="text-foreground">{direction.name}</span>
                        <span>{fmtMinutes(minutes)}</span>
                      </div>
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/5">
                        <div
                          className="h-full rounded-full bg-primary/70"
                          style={{ width: `${(minutes / maxDirectionMinutes) * 100}%` }}
                        />
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl bg-white/5 px-3 py-2.5">
      <div className="text-[11px] text-muted-foreground">{label}</div>
      <div className="mt-0.5 text-sm font-semibold tabular-nums">{value}</div>
    </div>
  )
}
