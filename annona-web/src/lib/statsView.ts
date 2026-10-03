/**
 * 年度统计视图纯函数（P2-01）：热力图布局、连续天数、日均与聚合口径。
 *
 * <p>职责切分沿 lib/evaluationView 先例：几何/口径数学全在这里（可表驱动测试），
 * StatsPanel 只做渲染。所有日期运算以 UTC 毫秒进行（YYYY-MM-DD ↔ UTC 天数），
 * 不用本地时区的 Date——热力图格子归属由后端按 Asia/Shanghai 日界聚合，前端只做
 * 确定的字符串日历算术，机器时区不影响任何结果。
 */

export interface DayMinutes {
  day: string
  verifiedMinutes: number
  selfReportedMinutes: number
}

/** 热力图 4 级色阶阈值（分钟）：≥1 / ≥30 / ≥60 / ≥120，0 级为空。 */
export const HEAT_THRESHOLDS = [1, 30, 60, 120] as const
export type HeatLevel = 0 | 1 | 2 | 3 | 4

/** 一行的总分钟（有效 + 补录；质量在颜色上区分，不在这里分家）。 */
export function dayTotal(day: DayMinutes): number {
  return day.verifiedMinutes + day.selfReportedMinutes
}

export function heatLevel(totalMinutes: number): HeatLevel {
  let level: HeatLevel = 0
  for (let i = 0; i < HEAT_THRESHOLDS.length; i++) {
    if (totalMinutes >= HEAT_THRESHOLDS[i]) {
      level = (i + 1) as HeatLevel
    }
  }
  return level
}

/** 该日是否有任意专注记录（连续天数口径：自报也算到过，颜色上如实降级显示）。 */
export function isDayActive(day: DayMinutes): boolean {
  return dayTotal(day) > 0
}

function parseDay(day: string): number {
  const [y, m, d] = day.split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

function formatDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

function addDays(ms: number, n: number): number {
  return ms + n * 86_400_000
}

/**
 * 连续天数：从今天（Asia/Shanghai）往回数有记录日。
 * 今天还没练不打断（与上游一致的宽容口径——今天还没过完）；昨天断了就从最近的记录日起算。
 * 说明：这里只能看到请求年份内的数据，跨年连续需两年数据拼接，v1 按年展示不作拼接。
 */
export function computeStreak(days: DayMinutes[], today: string): number {
  const active = new Set(days.filter(isDayActive).map((d) => d.day))
  let cursor = parseDay(today)
  if (!active.has(formatDay(cursor))) {
    cursor = addDays(cursor, -1)
  }
  let streak = 0
  while (active.has(formatDay(cursor))) {
    streak++
    cursor = addDays(cursor, -1)
  }
  return streak
}

/** 年内最长连续记录天数（跨列连续日历天，与展示列无关）。 */
export function computeLongestStreak(days: DayMinutes[], year: number): number {
  const yearStart = Date.UTC(year, 0, 1)
  const yearEnd = Date.UTC(year + 1, 0, 1)
  const sorted = days
    .filter(isDayActive)
    .map((d) => parseDay(d.day))
    .filter((ms) => ms >= yearStart && ms < yearEnd)
    .sort((a, b) => a - b)
  let best = 0
  let run = 0
  let prev = Number.NaN
  for (const ms of sorted) {
    run = ms === prev + 86_400_000 ? run + 1 : 1
    best = Math.max(best, run)
    prev = ms
  }
  return best
}

export interface Totals {
  verifiedTotal: number
  selfTotal: number
  /** 有任意记录的天数（日均的分母——按"练过的日子"平均，不按日历年摊薄）。 */
  activeDays: number
  dailyAverage: number
}

export function computeTotals(days: DayMinutes[]): Totals {
  let verifiedTotal = 0
  let selfTotal = 0
  let activeDays = 0
  for (const day of days) {
    verifiedTotal += day.verifiedMinutes
    selfTotal += day.selfReportedMinutes
    if (isDayActive(day)) {
      activeDays++
    }
  }
  const total = verifiedTotal + selfTotal
  return {
    verifiedTotal,
    selfTotal,
    activeDays,
    dailyAverage: activeDays > 0 ? Math.round(total / activeDays) : 0,
  }
}

/** 热力图格子：outside=年份外的补位格（渲染成透明，避免首尾列缺洞）。 */
export interface HeatCell {
  date: string
  level: HeatLevel
  /** 该日只有自报时长（verified=0 且 self>0）——用第二色相如实区分。 */
  selfOnly: boolean
  outside: boolean
}

export interface MonthLabel {
  label: string
  column: number
}

export interface HeatmapLayout {
  /** 周列（每列 7 天，周日开头），首列从 ≤1 月 1 日的第一个周日起。 */
  columns: HeatCell[][]
  monthLabels: MonthLabel[]
}

/** 周几行标签：固定展示第 0/2/4/6 行（日/二/四/六），沿上游热力图的省行画法。 */
export const WEEKDAY_LABELS = ['日', '', '二', '', '四', '', '六'] as const

/**
 * GitHub 式热力图布局：按周分列补齐年份外的头尾格。
 * 月份标签钉在「首个年内日落入新月份」的列上（GitHub 同款近似——不从每月 1 号精确对齐）。
 */
export function heatmapLayout(days: DayMinutes[], year: number): HeatmapLayout {
  const byDay = new Map(days.map((d) => [d.day, d]))
  const yearStart = Date.UTC(year, 0, 1)
  const yearEnd = Date.UTC(year + 1, 0, 1)
  const gridStart = yearStart - new Date(yearStart).getUTCDay() * 86_400_000

  const columns: HeatCell[][] = []
  for (let weekStart = gridStart; weekStart < yearEnd; weekStart += 7 * 86_400_000) {
    const column: HeatCell[] = []
    for (let i = 0; i < 7; i++) {
      const ms = weekStart + i * 86_400_000
      const date = formatDay(ms)
      const outside = ms < yearStart || ms >= yearEnd
      const data = byDay.get(date)
      const total = data ? dayTotal(data) : 0
      column.push({
        date,
        level: outside ? 0 : heatLevel(total),
        selfOnly: !outside && data != null && data.verifiedMinutes === 0 && data.selfReportedMinutes > 0,
        outside,
      })
    }
    columns.push(column)
  }

  const monthLabels: MonthLabel[] = []
  let prevMonth = -1
  columns.forEach((column, column_) => {
    const first = column.find((cell) => !cell.outside)
    if (!first) return
    const month = Number(first.date.slice(5, 7))
    if (month !== prevMonth) {
      monthLabels.push({ label: `${month}月`, column: column_ })
      prevMonth = month
    }
  })

  return { columns, monthLabels }
}

/** 色阶 1–4 对应的不透明度（color-mix 百分比），0 级由调用方画底色。 */
export const HEAT_OPACITY = [22, 45, 70, 100] as const

/** 当前"今天"（Asia/Shanghai，YYYY-MM-DD）——与后端 AppZones.DAILY 同口径。 */
export function shanghaiToday(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
}

/** 最近 n 天（含今天）的日期串，旧 → 新。 */
export function lastNDays(n: number, today: string): string[] {
  const end = parseDay(today)
  return Array.from({ length: n }, (_, i) => formatDay(addDays(end, i - n + 1)))
}
