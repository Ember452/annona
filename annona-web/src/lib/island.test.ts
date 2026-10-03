import { describe, expect, it } from 'vitest'

import {
  islandAriaLabel,
  mulberry32,
  plantVariantForTile,
  shouldUseIsland3D,
  stableHash,
  unlockedTileCount,
} from './island'

describe('小岛解锁数', () => {
  it('150 + 打卡数×5，封顶在总地块数', () => {
    expect(unlockedTileCount(0, 600)).toBe(150)
    expect(unlockedTileCount(10, 600)).toBe(200)
    expect(unlockedTileCount(10_000, 600)).toBe(600)
  })
})

describe('方向 → 植物形态', () => {
  it('无方向退化为序号轮换', () => {
    expect(plantVariantForTile(1, [])).toBe(1)
    expect(plantVariantForTile(3, [])).toBe(0)
  })

  it('同方向永远同形态；不同方向形态可分', () => {
    const ids = ['11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222']
    // 解锁序 1、3、5 归方向 A，2、4、6 归方向 B
    const a1 = plantVariantForTile(1, ids)
    expect(plantVariantForTile(3, ids)).toBe(a1)
    expect(plantVariantForTile(5, ids)).toBe(a1)
    const b1 = plantVariantForTile(2, ids)
    expect(plantVariantForTile(4, ids)).toBe(b1)
  })

  it('stableHash 确定且分布非平凡', () => {
    expect(stableHash('java')).toBe(stableHash('java'))
    expect(stableHash('java')).not.toBe(stableHash('javb'))
  })
})

describe('mulberry32', () => {
  it('同种子序列一致', () => {
    const a = mulberry32(42)
    const b = mulberry32(42)
    expect([a(), a(), a()]).toEqual([b(), b(), b()])
  })
})

describe('3D/2D 降级', () => {
  it('触屏主设备或窄视口走 2D', () => {
    expect(shouldUseIsland3D({ coarsePointer: false, viewportWidth: 1280 })).toBe(true)
    expect(shouldUseIsland3D({ coarsePointer: true, viewportWidth: 1280 })).toBe(false)
    expect(shouldUseIsland3D({ coarsePointer: false, viewportWidth: 767 })).toBe(false)
    expect(shouldUseIsland3D({ coarsePointer: false, viewportWidth: 768 })).toBe(true)
  })
})

describe('无障碍文案', () => {
  it('包含打卡/连续/时长/方向四要素', () => {
    const label = islandAriaLabel({ totalCheckins: 37, streak: 5, totalMinutes: 750, directionCount: 2 })
    expect(label).toContain('37 天打卡')
    expect(label).toContain('连续 5 天')
    expect(label).toContain('12.5 小时')
    expect(label).toContain('2 个方向')
  })
})
