import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { studyApi } from '@/api/study'
import { BREAK_MINUTES, usePomodoro } from './usePomodoro'

vi.mock('@/api/study', () => ({
  studyApi: {
    startSession: vi.fn(),
    finishSession: vi.fn().mockResolvedValue({ id: 's1' }),
    heartbeat: vi.fn().mockResolvedValue(undefined),
    reportBlur: vi.fn().mockResolvedValue(undefined),
  },
}))

vi.mock('@/hooks/useHeartbeat', () => ({ useHeartbeat: vi.fn() }))

const STORAGE_KEY = 'annona-study-pomodoro'

function seedSnapshot(overrides: Record<string, unknown>) {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      mode: 'focus',
      round: 1,
      completed: 0,
      focusMinutes: 25,
      isRunning: true,
      remaining: 2,
      expiresAt: Date.now() + 2_000,
      sessionId: 's1',
      ...overrides,
    }),
  )
}

describe('usePomodoro：到期转移（P1a-04 加固回归）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
  })

  afterEach(() => {
    // RTL 的 auto-cleanup 依赖全局 afterEach；本测试显式 import vitest API，需手动清
    cleanup()
    vi.useRealTimers()
    localStorage.clear()
  })

  it('专注自然到期 → 进入休息；休息段不被旧 deadline 跳过，且轮次只前进一次', async () => {
    seedSnapshot({})
    const { result } = renderHook(() => usePomodoro())

    // 快照恢复：仍在专注，剩余 2s
    expect(result.current.mode).toBe('focus')
    expect(result.current.hasSession).toBe(true)

    // 跨过专注截止时间
    act(() => {
      vi.advanceTimersByTime(2_500)
    })
    await act(async () => {}) // flush finish promise
    expect(result.current.mode).toBe('break')
    expect(result.current.completed).toBe(1)
    expect(result.current.isRunning).toBe(true)

    // 修复前：isRunning 保持 true 使 tick effect 不重建，250ms 后旧 focus deadline
    // 再次触发 expire → 直接跳回 focus，5 分钟休息整段被吞（round 同时 +1）
    act(() => {
      vi.advanceTimersByTime(3_000)
    })
    expect(result.current.mode).toBe('break')
    expect(result.current.isRunning).toBe(true)
    expect(result.current.round).toBe(1)
    expect(result.current.remaining).toBeGreaterThan(BREAK_MINUTES * 60 - 10)

    // 休息自然到期 → 回到 focus 待开始，轮次 +1
    act(() => {
      vi.advanceTimersByTime(BREAK_MINUTES * 60_000)
    })
    expect(result.current.mode).toBe('focus')
    expect(result.current.round).toBe(2)
    expect(result.current.completed).toBe(1)
    expect(result.current.isRunning).toBe(false)
  })

  it('恢复时专注快照已过期：补 finish 并直接进入休息', async () => {
    seedSnapshot({ expiresAt: Date.now() - 60_000, remaining: 0 })
    const { result } = renderHook(() => usePomodoro())
    await act(async () => {})

    expect(result.current.mode).toBe('break')
    expect(result.current.completed).toBe(1)
    expect(result.current.isRunning).toBe(true)
  })
})

describe('usePomodoro：沉浸模式开始（P2-03）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('start 携带 mode=IMMERSIVE 建会话，本地轮转状态不受影响', async () => {
    ;(studyApi.startSession as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ id: 's-immersive' })
    const { result } = renderHook(() => usePomodoro())

    await act(async () => {
      await result.current.start('dir-immersive', 'IMMERSIVE')
    })

    expect(studyApi.startSession).toHaveBeenCalledWith({
      directionId: 'dir-immersive',
      plannedMinutes: 25,
      mode: 'IMMERSIVE',
    })
    expect(result.current.hasSession).toBe(true)
    expect(result.current.isRunning).toBe(true)
  })
})
