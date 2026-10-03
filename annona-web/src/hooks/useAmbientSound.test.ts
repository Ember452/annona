import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { SceneType } from '@/lib/scene'
import { useAmbientSound } from './useAmbientSound'

/** 记录实例的 Audio 桩：jsdom 的 play() 未实现，hook 的全部行为都落在这些可观察方法上。
 *  src 模拟真实 HTMLAudioElement 的反射语义：getter 恒返回解析后的绝对地址（空串保持空串），
 *  hook 的「同 src 不重复加载」分支依赖这一行为。 */
class MockAudio {
  static instances: MockAudio[] = []
  loop = false
  volume = 1
  preload = ''
  private _src = ''
  paused = true
  ended = false
  load = vi.fn()
  pause = vi.fn(() => {
    this.paused = true
  })
  play = vi.fn(() => {
    this.paused = false
    return Promise.resolve()
  })

  constructor() {
    MockAudio.instances.push(this)
  }

  get src() {
    return this._src
  }

  set src(value: string) {
    this._src = value === '' ? '' : new URL(value, window.location.origin).href
  }

  /** play 转为拒绝（模拟自动播放策略拦截），后续调用恢复成功 */
  failNextPlay() {
    this.play.mockImplementationOnce(() => {
      this.paused = true
      return Promise.reject(new Error('NotAllowedError'))
    })
  }
}

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true })
}

function fireVisibility() {
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('useAmbientSound', () => {
  let originalHidden: boolean
  beforeEach(() => {
    MockAudio.instances = []
    vi.stubGlobal('Audio', MockAudio)
    originalHidden = document.hidden
    localStorage.clear()
  })

  afterEach(() => {
    // RTL 的 auto-cleanup 依赖全局 afterEach；本测试显式 import vitest API，需手动清
    cleanup()
    vi.unstubAllGlobals()
    setHidden(originalHidden)
    localStorage.clear()
  })

  it('挂载即创建单例 Audio：循环播放、默认音量 0.3、按场景加载资源并尝试播放', () => {
    const { result } = renderHook(() => useAmbientSound('rain'))
    expect(MockAudio.instances).toHaveLength(1)
    const audio = MockAudio.instances[0]
    expect(audio.loop).toBe(true)
    expect(audio.volume).toBe(0.3)
    expect(audio.src).toBe(new URL('/audio/rain.wav', window.location.origin).href)
    expect(audio.load).toHaveBeenCalled()
    expect(audio.play).toHaveBeenCalled()
    expect(result.current.volume).toBe(0.3)
  })

  it('场景切换换 src；同 src 重复渲染不重新加载', () => {
    const { rerender } = renderHook((props: { scene: SceneType }) => useAmbientSound(props.scene), {
      initialProps: { scene: 'rain' },
    })
    const audio = MockAudio.instances[0]
    const loadCount = audio.load.mock.calls.length

    rerender({ scene: 'snow' })
    expect(audio.src).toBe(new URL('/audio/snow.wav', window.location.origin).href)
    expect(audio.load.mock.calls.length).toBe(loadCount + 1)

    rerender({ scene: 'snow' })
    expect(audio.load.mock.calls.length).toBe(loadCount + 1)
  })

  it('切到暖云（无音频）暂停并清空 src', () => {
    const { rerender } = renderHook((props: { scene: SceneType }) => useAmbientSound(props.scene), {
      initialProps: { scene: 'rain' },
    })
    const audio = MockAudio.instances[0]
    audio.play()
    expect(audio.paused).toBe(false)

    rerender({ scene: 'cloud' })
    expect(audio.pause).toHaveBeenCalled()
    expect(audio.src).toBe('')
  })

  it('play 被自动播放策略拦截时，注册一次性首次点击重试', async () => {
    const { rerender } = renderHook((props: { scene: SceneType }) => useAmbientSound(props.scene), {
      initialProps: { scene: 'rain' },
    })
    const audio = MockAudio.instances[0]
    const playsBefore = audio.play.mock.calls.length

    rerender({ scene: 'cloud' }) // 清 src，制造「重新加载」路径
    audio.failNextPlay()
    rerender({ scene: 'rain' }) // 重新加载 → play 被拦截
    expect(audio.play.mock.calls.length).toBe(playsBefore + 1)

    // play 的拒绝回调在微任务里注册 click 监听，先冲刷微任务再模拟交互
    await act(async () => {})
    // 自动播放策略拦截后，首次用户交互应恢复播放
    document.dispatchEvent(new Event('click'))
    expect(audio.play.mock.calls.length).toBe(playsBefore + 2)
  })

  it('切走标签页暂停在播的音频，回来恢复；离开时本就静默则回来不播', () => {
    renderHook(() => useAmbientSound('rain'))
    const audio = MockAudio.instances[0]
    audio.play()
    expect(audio.paused).toBe(false)

    act(() => {
      setHidden(true)
      fireVisibility()
    })
    expect(audio.paused).toBe(true)

    act(() => {
      setHidden(false)
      fireVisibility()
    })
    const playsAfterResume = audio.play.mock.calls.length
    expect(playsAfterResume).toBeGreaterThanOrEqual(2)

    // 回来后手动暂停再切走：这次离开时不在播，回来不应触发恢复
    audio.pause()
    act(() => {
      setHidden(true)
      fireVisibility()
    })
    act(() => {
      setHidden(false)
      fireVisibility()
    })
    expect(audio.play.mock.calls.length).toBe(playsAfterResume)
  })

  it('setVolume 夹紧到 [0,1] 并同步到实例', () => {
    const { result } = renderHook(() => useAmbientSound('rain'))
    const audio = MockAudio.instances[0]
    act(() => result.current.setVolume(1.5))
    expect(result.current.volume).toBe(1)
    expect(audio.volume).toBe(1)
    act(() => result.current.setVolume(-2))
    expect(result.current.volume).toBe(0)
    act(() => result.current.setVolume(0.55))
    expect(audio.volume).toBe(0.55)
  })
})
