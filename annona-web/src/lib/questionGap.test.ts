import { describe, expect, it } from 'vitest'

import { followUpGapWarning, strictCapacityMessage, usableFollowUpCount } from './questionGap'

describe('usableFollowUpCount', () => {
  it('空题干追问不计入可用数（与后端容量口径一致）', () => {
    expect(
      usableFollowUpCount([
        { question: '追问1' },
        { question: '   ' },
        { question: null },
      ]),
    ).toBe(1)
  })

  it('null/undefined 追问列表按 0 算', () => {
    expect(usableFollowUpCount(null)).toBe(0)
    expect(usableFollowUpCount(undefined)).toBe(0)
  })
})

describe('followUpGapWarning', () => {
  it('实际不足目标时给缺口文案', () => {
    expect(followUpGapWarning(1, 3)).toBe('追问不足：实际 1 / 目标 3')
  })

  it('达标与超额返回 null', () => {
    expect(followUpGapWarning(3, 3)).toBeNull()
    expect(followUpGapWarning(4, 3)).toBeNull()
  })
})

describe('strictCapacityMessage', () => {
  const option = (count: number, available: number, selectable: boolean) => ({
    followUpCount: count,
    availableQuestionCount: available,
    selectable,
  })

  it('期望档位可选时不提示', () => {
    expect(
      strictCapacityMessage([option(0, 5, true), option(1, 3, true), option(2, 2, false)], 3, 1),
    ).toBeNull()
  })

  it('题量本身不足时提示先生成/启用', () => {
    const msg = strictCapacityMessage(
      [option(0, 2, false), option(1, 1, false)],
      3,
      1,
    )
    expect(msg).toContain('仅有 2 道启用题目')
    expect(msg).toContain('不足 3 道')
  })

  it('题量够但追问深度不足时给"最多可严格保证 M 个追问"', () => {
    // 4 道题可用，但只有 2 道带 ≥2 个追问 → 期望档 2 不可选，最高可保证档 = 1
    const msg = strictCapacityMessage(
      [option(0, 4, true), option(1, 4, true), option(2, 2, false), option(3, 0, false)],
      3,
      2,
    )
    expect(msg).toContain('每题最多可严格保证 1 个追问')
    expect(msg).toContain('需要 3 道主问题')
  })
})
