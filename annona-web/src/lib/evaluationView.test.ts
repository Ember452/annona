import { describe, expect, it } from 'vitest'

import {
  formatScore,
  radarAxes,
  radarPolygon,
  shouldPoll,
} from './evaluationView'
import type { QuestionEvaluation } from '@/types/evaluation'

function q(partial: Partial<QuestionEvaluation> & { questionId: string }): QuestionEvaluation {
  return {
    followUpIndex: 0, score: null, feedback: null,
    strengths: [], improvements: [], fallbackUsed: false, ...partial,
  } as QuestionEvaluation
}

describe('radarAxes：按题聚合、追问并入主问题、全降级为 null', () => {
  it('同题主问与追问取均值，多题保持出现顺序', () => {
    const axes = radarAxes([
      q({ questionId: 'a', followUpIndex: 0, score: 80 }),
      q({ questionId: 'a', followUpIndex: 1, score: 60 }),
      q({ questionId: 'b', followUpIndex: 0, score: 40 }),
    ])
    expect(axes).toEqual([
      { label: '第1题', value: 70 },
      { label: '第2题', value: 40 },
    ])
  })

  it('某题全部降级（score null / fallbackUsed）→ 该轴 value 为 null', () => {
    const axes = radarAxes([
      q({ questionId: 'a', score: null, fallbackUsed: true }),
    ])
    expect(axes[0].value).toBeNull()
  })
})

describe('radarPolygon：几何纯计算', () => {
  it('空轴集返回空点集（不画崩）', () => {
    expect(radarPolygon([])).toEqual([])
  })

  it('单轴满分落在正上方（角度 -90°，x=圆心、y<圆心）', () => {
    const [p] = radarPolygon([{ label: 'x', value: 100 }], 200)
    expect(p.x).toBeCloseTo(100, 2)
    expect(p.y).toBeLessThan(100)
  })

  it('value=null 视作 0 落圆心', () => {
    const [p] = radarPolygon([{ label: 'x', value: null }], 200)
    expect(p.x).toBeCloseTo(100, 2)
    expect(p.y).toBeCloseTo(100, 2)
  })
})

describe('shouldPoll / formatScore', () => {
  it('PENDING/RUNNING 继续轮询，DONE/FAILED 停', () => {
    expect(shouldPoll('PENDING')).toBe(true)
    expect(shouldPoll('RUNNING')).toBe(true)
    expect(shouldPoll('DONE')).toBe(false)
    expect(shouldPoll('FAILED')).toBe(false)
    expect(shouldPoll(undefined)).toBe(false)
  })

  it('未出分显示占位破折号而非 0（不给假分）', () => {
    expect(formatScore({ compositeScore: null, status: 'RUNNING' })).toBe('—')
    expect(formatScore({ compositeScore: 85, status: 'DONE' })).toBe('85')
  })
})
