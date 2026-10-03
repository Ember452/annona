import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LongPressExit from './LongPressExit'

describe('LongPressExit（沉浸层长按退出）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    // RTL 的 auto-cleanup 依赖全局 afterEach；本测试显式 import vitest API，需手动清
    cleanup()
    vi.useRealTimers()
  })

  function firePointer(element: Element, type: string) {
    fireEvent(element, new Event(type, { bubbles: true }))
  }

  it('按住满 1400ms 触发退出', () => {
    const onExit = vi.fn()
    render(<LongPressExit onExit={onExit} />)
    const button = screen.getByText('长按退出沉浸')

    act(() => {
      firePointer(button, 'pointerdown')
      vi.advanceTimersByTime(1399)
    })
    expect(onExit).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(onExit).toHaveBeenCalledTimes(1)
  })

  it('提前松开取消：不触发退出，重新按住重新计时', () => {
    const onExit = vi.fn()
    render(<LongPressExit onExit={onExit} />)
    const button = screen.getByText('长按退出沉浸')

    act(() => {
      firePointer(button, 'pointerdown')
      vi.advanceTimersByTime(700)
    })
    act(() => {
      firePointer(button, 'pointerup')
      vi.advanceTimersByTime(10_000)
    })
    expect(onExit).not.toHaveBeenCalled()

    // 重新按住需要完整的 1400ms
    act(() => {
      firePointer(button, 'pointerdown')
      vi.advanceTimersByTime(1399)
    })
    expect(onExit).not.toHaveBeenCalled()
    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(onExit).toHaveBeenCalledTimes(1)
  })

  it('按住中指针移出（pointerleave）取消', () => {
    const onExit = vi.fn()
    render(<LongPressExit onExit={onExit} />)
    const button = screen.getByText('长按退出沉浸')

    act(() => {
      // pointerleave 不冒泡，须走 RTL 的专用触发器（内部直发到目标元素）
      fireEvent.pointerDown(button)
      fireEvent.pointerLeave(button)
      vi.advanceTimersByTime(10_000)
    })
    expect(onExit).not.toHaveBeenCalled()
  })
})
