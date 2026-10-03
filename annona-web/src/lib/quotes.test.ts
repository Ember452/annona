import { describe, expect, it } from 'vitest'

import { GENERAL_QUOTES, pickIndex, randomQuote } from '@/lib/quotes'

describe('鼓励语', () => {
  it('pickIndex 连续两次不取同一下标', () => {
    // rand 命中 prev 时顺移一位
    expect(pickIndex(50, 49, () => 0.999)).toBe(0)
    expect(pickIndex(50, 7, () => 7 / 50)).toBe(8)
    expect(pickIndex(50, -1, () => 0)).toBe(0)
  })

  it('pickIndex 单元素池不除零', () => {
    expect(pickIndex(1, 0, () => 0)).toBe(0)
  })

  it('randomQuote 连续调用不与上一句重复', () => {
    let prev = randomQuote('rain')
    for (let i = 0; i < 100; i++) {
      const next = randomQuote('snow')
      expect(next).not.toBe(prev)
      prev = next
    }
  })

  it('通用语录池规模 47 句且全部非空', () => {
    expect(GENERAL_QUOTES).toHaveLength(47)
    for (const q of GENERAL_QUOTES) {
      expect(q.length).toBeGreaterThan(0)
    }
  })
})
