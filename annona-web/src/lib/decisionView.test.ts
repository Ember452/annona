import { describe, expect, it } from 'vitest'

import {
  applyRejection,
  emptyHint,
  groupBySession,
  isRejected,
  ruleLabel,
} from './decisionView'
import type { DecisionTrace } from '@/types/decision'

function trace(partial: Partial<DecisionTrace> & { traceId: string }): DecisionTrace {
  return {
    sessionId: 's1', directionId: 'd1', ruleKey: 'WEAK_DIRECTION', action: 'RAISE_DIFFICULTY',
    reason: '均分低加压', rejectedBy: null, createdAt: '2026-09-30T00:00:00Z', ...partial,
  } as DecisionTrace
}

describe('ruleLabel：已知键中文、未知键原样', () => {
  it('映射核心规则键', () => {
    expect(ruleLabel('FORGETTING_CURVE')).toBe('遗忘曲线')
    expect(ruleLabel('WEAK_DIRECTION')).toBe('薄弱方向')
    expect(ruleLabel('NO_STUDY_RECORD')).toBe('仅面试表现')
  })
  it('未知键原样回显（前端不因后端新增规则崩）', () => {
    expect(ruleLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})

describe('applyRejection：命中项标 USER 驳回、不可变', () => {
  it('只改目标条，返回新数组', () => {
    const before = [trace({ traceId: 'a' }), trace({ traceId: 'b' })]
    const after = applyRejection(before, 'a')
    expect(after.find((t) => t.traceId === 'a')!.rejectedBy).toBe('USER')
    expect(after.find((t) => t.traceId === 'b')!.rejectedBy).toBeNull()
    expect(before.find((t) => t.traceId === 'a')!.rejectedBy).toBeNull() // 原数组未被改
  })
})

describe('emptyHint / isRejected', () => {
  it('空列表给诚实提示，非空给 null', () => {
    expect(emptyHint([])).toContain('默认策略')
    expect(emptyHint([trace({ traceId: 'a' })])).toBeNull()
  })
  it('rejectedBy=USER 判已驳回', () => {
    expect(isRejected(trace({ traceId: 'a', rejectedBy: 'USER' }))).toBe(true)
    expect(isRejected(trace({ traceId: 'a', rejectedBy: null }))).toBe(false)
  })
})

describe('groupBySession：按会话聚合、保持最近优先顺序', () => {
  it('同会话多条聚一组，组序按首次出现', () => {
    const groups = groupBySession([
      trace({ traceId: 'a', sessionId: 's2' }),
      trace({ traceId: 'b', sessionId: 's1' }),
      trace({ traceId: 'c', sessionId: 's2' }),
    ])
    expect(groups.map((g) => g.sessionId)).toEqual(['s2', 's1'])
    expect(groups[0].traces.map((t) => t.traceId)).toEqual(['a', 'c'])
  })
  it('空输入返回空数组', () => {
    expect(groupBySession([])).toEqual([])
  })
})
