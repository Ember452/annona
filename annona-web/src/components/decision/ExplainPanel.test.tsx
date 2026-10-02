import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/request'
import { decisionApi } from '@/api/decisions'
import type { DecisionTrace } from '@/types/decision'

import { ExplainPanel } from './ExplainPanel'

vi.mock('@/api/decisions', () => ({
  decisionApi: { sessionTraces: vi.fn(), recent: vi.fn(), reject: vi.fn() },
}))

const trace = (rejectedBy: string | null): DecisionTrace => ({
  traceId: 't1',
  sessionId: 's1',
  directionId: 'd1',
  ruleKey: 'WEAK_DIRECTION',
  action: 'RAISE_DIFFICULTY',
  reason: '该方向最近 3 场有效样本均分 48.0 低于弱项线 60——本规则将本场难度上调一档',
  rejectedBy,
  createdAt: '2026-10-01T00:00:00Z',
})

/**
 * 面板写路径的行为规格（P1c-07 的可解释前提）：驳回失败不能静默。
 * 旧实现把 error 只在 traces === null 的分支里渲染，于是"加载成功后再驳回失败"
 * 这条最常走的路径上错误永远不可见，注释承诺的"由下次 load 修正"也没有触发点。
 */
describe('ExplainPanel 驳回失败与回滚', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // 没有 cleanup 时上一个用例的 DOM 会残留，getByTestId 会报“多个元素”（auth.test.tsx 同款约定）
  afterEach(() => {
    cleanup()
  })

  it('驳回失败（3201）：错误可见，且乐观"已驳回"态被服务端数据覆盖', async () => {
    vi.mocked(decisionApi.sessionTraces)
      .mockResolvedValueOnce([trace(null)])
      .mockResolvedValueOnce([trace(null)])
    vi.mocked(decisionApi.reject)
      .mockRejectedValue(new ApiError('这条决策你已经驳回过了', 3201))

    render(<ExplainPanel sessionId="s1" />)
    await screen.findByTestId('dc-panel')
    await act(async () => {
      fireEvent.click(screen.getByTestId('dc-reject-t1'))
    })
    const error = await screen.findByTestId('dc-error')
    expect(error.textContent).toContain('已经驳回')
    // 服务端没记上就不显示已驳回，按钮必须还能点（用户能重试或换一条）
    expect(screen.queryByTestId('dc-rejected')).toBeNull()
    expect(screen.getByTestId('dc-reject-t1')).not.toBeNull()
    expect(decisionApi.sessionTraces).toHaveBeenCalledTimes(2)
  })

  it('驳回成功：不显示错误，条目转为已驳回态且不额外重拉', async () => {
    vi.mocked(decisionApi.sessionTraces).mockResolvedValue([trace(null)])
    vi.mocked(decisionApi.reject).mockResolvedValue(1)

    render(<ExplainPanel sessionId="s1" />)
    await screen.findByTestId('dc-panel')
    await act(async () => {
      fireEvent.click(screen.getByTestId('dc-reject-t1'))
    })
    // 成功时乐观态就是终态：标成已驳回、不报错、不额外重拉
    expect(screen.getByTestId('dc-rejected')).not.toBeNull()
    expect(screen.queryByTestId('dc-error')).toBeNull()
    expect(decisionApi.sessionTraces).toHaveBeenCalledTimes(1)
  })

  it('首次加载失败：走错误分支，不渲染面板', async () => {
    vi.mocked(decisionApi.sessionTraces)
      .mockRejectedValue(new ApiError('无法连接后端', undefined))

    render(<ExplainPanel sessionId="s1" />)

    expect(await screen.findByTestId('dc-error')).not.toBeNull()
    expect(screen.queryByTestId('dc-panel')).toBeNull()
  })
})
