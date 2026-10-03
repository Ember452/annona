import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { SceneProvider, useScene } from './scene'

function Probe() {
  const { scene, setScene, copy } = useScene()
  return (
    <div>
      <span data-testid="scene">{scene}</span>
      <span data-testid="label">{copy.label}</span>
      <button data-testid="to-snow" onClick={() => setScene('snow')}>
        snow
      </button>
      <button data-testid="to-cloud" onClick={() => setScene('cloud')}>
        cloud
      </button>
    </div>
  )
}

describe('SceneProvider', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.dataset.scene = 'rain'
  })

  afterEach(() => {
    // RTL 的 auto-cleanup 依赖全局 afterEach；本测试显式 import vitest API，需手动清
    cleanup()
    localStorage.clear()
    document.documentElement.dataset.scene = 'rain'
  })

  it('默认雨林场景并同步到 html[data-scene]；切换后 CSS 挂点与 localStorage 一致', () => {
    render(
      <SceneProvider>
        <Probe />
      </SceneProvider>,
    )
    expect(screen.getByTestId('scene').textContent).toBe('rain')
    expect(document.documentElement.dataset.scene).toBe('rain')

    fireEvent.click(screen.getByTestId('to-snow'))
    expect(screen.getByTestId('scene').textContent).toBe('snow')
    expect(document.documentElement.dataset.scene).toBe('snow')
    expect(localStorage.getItem('annona-scene')).toBe('snow')
    expect(screen.getByTestId('label').textContent).toBe('雪日')
  })

  it('重挂载后从 localStorage 恢复上次场景（含非法值回落）', () => {
    localStorage.setItem('annona-scene', 'cloud')
    const { unmount } = render(
      <SceneProvider>
        <Probe />
      </SceneProvider>,
    )
    expect(screen.getByTestId('scene').textContent).toBe('cloud')
    unmount()

    localStorage.setItem('annona-scene', 'hacker')
    render(
      <SceneProvider>
        <Probe />
      </SceneProvider>,
    )
    expect(screen.getByTestId('scene').textContent).toBe('rain')
    expect(document.documentElement.dataset.scene).toBe('rain')
  })

  it('provider 外用 useScene 抛错（防误用）', () => {
    function Orphan() {
      useScene()
      return null
    }
    // 屏蔽 React 的错误边界日志噪声
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Orphan />)).toThrow('useScene must be used within a SceneProvider')
    spy.mockRestore()
  })
})
