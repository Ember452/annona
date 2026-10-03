import { describe, expect, it } from 'vitest'

import {
  computeLongestStreak,
  computeStreak,
  computeTotals,
  dayTotal,
  heatmapLayout,
  heatLevel,
  isDayActive,
  lastNDays,
} from './statsView'

function day(day: string, verifiedMinutes = 0, selfReportedMinutes = 0) {
  return { day, verifiedMinutes, selfReportedMinutes }
}

describe('热力等级', () => {
  it('阈值边界：0/1/29/30/59/60/119/120/121', () => {
    expect(heatLevel(0)).toBe(0)
    expect(heatLevel(1)).toBe(1)
    expect(heatLevel(29)).toBe(1)
    expect(heatLevel(30)).toBe(2)
    expect(heatLevel(59)).toBe(2)
    expect(heatLevel(60)).toBe(3)
    expect(heatLevel(119)).toBe(3)
    expect(heatLevel(120)).toBe(4)
    expect(heatLevel(121)).toBe(4)
  })

  it('总量与活跃判定：质量相加，任一分量 > 0 即活跃', () => {
    expect(dayTotal(day('2026-01-01', 30, 10))).toBe(40)
    expect(isDayActive(day('2026-01-01', 0, 5))).toBe(true)
    expect(isDayActive(day('2026-01-01', 0, 0))).toBe(false)
  })
})

describe('连续天数', () => {
  it('今天已练：从今天往回数', () => {
    const days = [day('2026-03-01', 30), day('2026-03-02', 30), day('2026-03-03', 30)]
    expect(computeStreak(days, '2026-03-03')).toBe(3)
  })

  it('今天还没练不打断（宽容口径），昨天断了则断', () => {
    const days = [day('2026-03-01', 30), day('2026-03-02', 30)]
    expect(computeStreak(days, '2026-03-03')).toBe(2)
    expect(computeStreak(days, '2026-03-05')).toBe(0)
  })

  it('仅自报的日子也算连续（颜色层再区分质量）', () => {
    const days = [day('2026-03-02', 0, 45)]
    expect(computeStreak(days, '2026-03-02')).toBe(1)
  })
})

describe('年内最长连续', () => {
  it('取最长段；跨月连续照算', () => {
    const days = [
      day('2026-01-30', 10),
      day('2026-01-31', 10),
      day('2026-02-01', 10),
      day('2026-02-02', 10),
      day('2026-03-05', 10),
    ]
    expect(computeLongestStreak(days, 2026)).toBe(4)
  })

  it('跨年数据不接续（按年展示的已知边界）', () => {
    const days = [day('2025-12-31', 10), day('2026-01-01', 10)]
    expect(computeLongestStreak(days, 2026)).toBe(1)
  })
})

describe('聚合总量', () => {
  it('日均按有记录日摊，空数据全零', () => {
    const days = [day('2026-01-01', 30, 10), day('2026-01-02', 60), day('2026-01-03')]
    const totals = computeTotals(days)
    expect(totals.verifiedTotal).toBe(90)
    expect(totals.selfTotal).toBe(10)
    expect(totals.activeDays).toBe(2)
    expect(totals.dailyAverage).toBe(50)

    expect(computeTotals([]).dailyAverage).toBe(0)
  })
})

describe('热力图布局', () => {
  it('2026-01-01 是周四：首列从 2025-12-28（周日）补位，共 53 列', () => {
    const layout = heatmapLayout([], 2026)
    expect(layout.columns).toHaveLength(53)
    const first = layout.columns[0]
    expect(first[0].date).toBe('2025-12-28')
    expect(first[0].outside).toBe(true)
    expect(first[4].date).toBe('2026-01-01')
    expect(first[4].outside).toBe(false)
    // 尾格：2026-12-31（周四）落在最后一列
    const lastColumn = layout.columns[52]
    expect(lastColumn.filter((cell) => !cell.outside)).toHaveLength(5)
  })

  it('月份标签钉在月份更替的列：全年 12 个，1 月@0、2 月@5、3 月@9', () => {
    const layout = heatmapLayout([], 2026)
    expect(layout.monthLabels).toHaveLength(12)
    expect(layout.monthLabels[0]).toEqual({ label: '1月', column: 0 })
    expect(layout.monthLabels[1]).toEqual({ label: '2月', column: 5 })
    expect(layout.monthLabels[2]).toEqual({ label: '3月', column: 9 })
  })

  it('有记录的日子落对格子；仅自报日标 selfOnly', () => {
    const layout = heatmapLayout(
      [day('2026-01-01', 45, 0), day('2026-01-02', 0, 30)],
      2026,
    )
    const cell1 = layout.columns[0][4]
    expect(cell1.level).toBe(2)
    expect(cell1.selfOnly).toBe(false)
    const cell2 = layout.columns[0][5]
    expect(cell2.level).toBe(2)
    expect(cell2.selfOnly).toBe(true)
  })
})

describe('最近 N 天', () => {
  it('旧 → 新，含今天；跨月/跨年字符串正确', () => {
    expect(lastNDays(3, '2026-03-02')).toEqual(['2026-02-28', '2026-03-01', '2026-03-02'])
    expect(lastNDays(2, '2026-01-01')).toEqual(['2025-12-31', '2026-01-01'])
  })
})
