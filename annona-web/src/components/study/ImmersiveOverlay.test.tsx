import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { SceneProvider } from '@/stores/scene'
import ImmersiveOverlay from './ImmersiveOverlay'

vi.mock('@/hooks/useAmbientSound', () => ({
  useAmbientSound: () => ({ volume: 0.3, setVolume: vi.fn() }),
}))

function renderOverlay(onExit = () => {}) {
  return render(
    <SceneProvider>
      <ImmersiveOverlay
        mode="focus"
        round={1}
        completed={2}
        remaining={17 * 60}
        totalSeconds={25 * 60}
        onExit={onExit}
      />
    </SceneProvider>,
  )
}

describe('ImmersiveOverlay（沉浸覆盖层）', () => {
  afterEach(() => {
    // RTL 的 auto-cleanup 依赖全局 afterEach；本测试显式 import vitest API，需手动清
    cleanup()
    document.body.classList.remove('annona-immersive')
  })

  it('渲染计时环与一句鼓励语；挂载即给 body 加滚动锁，卸载还原', () => {
    const { unmount } = renderOverlay()
    expect(document.body.classList.contains('annona-immersive')).toBe(true)
    // 计时环走 aria（FocusTimer 的无障碍出口），鼓励语由 randomQuote 池给出
    expect(document.querySelector('[aria-label*="番茄钟"]')).not.toBeNull()
    expect(screen.getByText(/「.*」/)).not.toBeNull()
    expect(screen.getByText('长按退出沉浸')).not.toBeNull()

    unmount()
    expect(document.body.classList.contains('annona-immersive')).toBe(false)
  })
})
