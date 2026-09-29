import { describe, expect, it } from 'vitest'

import {
  flowReducer,
  initialFlow,
  slotKey,
  slotsByOrder,
  type FlowState,
} from '@/lib/interviewFlow'
import type { SessionView, SlotView } from '@/types/interview'

function slot(order: number, questionId: string, followUpIndex: number): SlotView {
  return { order, questionId, followUpIndex, questionText: `题${questionId}-${followUpIndex}`,
    answered: false, answerText: null }
}

function view(totalCount: number, currentIndex: number, slots: SlotView[]): SessionView {
  return { id: 's1', directionId: 'd1', status: 'RESUMABLE', currentIndex, totalCount,
    answeredCount: 0, slots, skippedReasons: [], startedAt: '2026-09-29T00:00:00Z' }
}

describe('interviewFlow 面试作答状态机', () => {
  it('OPEN 以服务端 currentIndex 定位恢复位（不是第一题）', () => {
    const v = view(3, 2, [slot(0, 'a', 0), slot(1, 'b', 0), slot(2, 'c', 0)])
    const next = flowReducer(initialFlow, { type: 'OPEN', view: v })
    expect(next.phase).toBe('answering')
    expect(next.order).toBe(2)
  })

  it('OPEN 的 currentIndex 越界时钳到最后一题（前后端竞态不白屏）', () => {
    const v = view(2, 5, [slot(0, 'a', 0), slot(1, 'b', 0)])
    expect(flowReducer(initialFlow, { type: 'OPEN', view: v }).order).toBe(1)
  })

  it('ADVANCE 逐题前进且在末题停住', () => {
    const v = view(2, 0, [slot(0, 'a', 0), slot(1, 'b', 0)])
    let s = flowReducer(initialFlow, { type: 'OPEN', view: v })
    s = flowReducer(s, { type: 'ADVANCE' })
    expect(s.order).toBe(1)
    s = flowReducer(s, { type: 'ADVANCE' })
    expect(s.order).toBe(1)   // 已交卷前不越界
  })

  it('FAIL 保留草稿与位次，只记错误（改一改即可重试）', () => {
    const v = view(2, 0, [slot(0, 'a', 0)])
    let s: FlowState = flowReducer(initialFlow, { type: 'OPEN', view: v })
    s = flowReducer(s, { type: 'DRAFT', slotKey: slotKey(v.slots[0]), text: '我的答案' })
    s = flowReducer(s, { type: 'BUSY', busy: true })
    s = flowReducer(s, { type: 'FAIL', message: '该题已答过' })
    expect(s.phase).toBe('answering')
    expect(s.order).toBe(0)
    expect(s.drafts[slotKey(v.slots[0])]).toBe('我的答案')
    expect(s.busy).toBe(false)
    expect(s.error).toBe('该题已答过')
  })

  it('SUBMITTED 进终态；RESET 回表单', () => {
    const v = view(1, 0, [slot(0, 'a', 0)])
    let s = flowReducer(initialFlow, { type: 'OPEN', view: v })
    s = flowReducer(s, { type: 'SUBMITTED', view: v })
    expect(s.phase).toBe('submitted')
    expect(flowReducer(s, { type: 'RESET' }).phase).toBe('form')
  })

  it('slotsByOrder 按位次分组并排序（乱序输入不乱组）', () => {
    const v = view(2, 0, [slot(1, 'b', 0), slot(0, 'a', 1), slot(0, 'a', 0)])
    const groups = slotsByOrder(v)
    expect(groups).toHaveLength(2)
    expect(groups[0].map((s) => s.followUpIndex)).toEqual([1, 0])
    expect(slotsByOrder(null)).toEqual([])
  })
})
