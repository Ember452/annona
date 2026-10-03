import { describe, expect, it } from 'vitest'

import { asSceneType, asThemeKey, SCENE_COPY, sceneToThemeKey, themeKeyToScene } from '@/lib/scene'

describe('场景元数据', () => {
  it('asSceneType 对空值与非法值回落默认场景', () => {
    expect(asSceneType(null)).toBe('rain')
    expect(asSceneType(undefined)).toBe('rain')
    expect(asSceneType('')).toBe('rain')
    expect(asSceneType('nonsense')).toBe('rain')
  })

  it('asSceneType 认识合法场景', () => {
    expect(asSceneType('rain')).toBe('rain')
    expect(asSceneType('snow')).toBe('snow')
    expect(asSceneType('cloud')).toBe('cloud')
  })

  it('asThemeKey 对空值与非法值回落 rainforest（与后端 V1 列默认一致）', () => {
    expect(asThemeKey(null)).toBe('rainforest')
    expect(asThemeKey('')).toBe('rainforest')
    expect(asThemeKey('jungle')).toBe('rainforest')
    expect(asThemeKey('snow')).toBe('snow')
    expect(asThemeKey('cloud')).toBe('cloud')
  })

  it('ThemeKey 与 SceneType 往返映射，rainforest 对应 CSS 场景 rain', () => {
    expect(themeKeyToScene('rainforest')).toBe('rain')
    expect(themeKeyToScene('snow')).toBe('snow')
    expect(themeKeyToScene('cloud')).toBe('cloud')
    for (const key of ['rainforest', 'snow', 'cloud'] as const) {
      expect(sceneToThemeKey(themeKeyToScene(key))).toBe(key)
    }
  })

  it('每个场景都有完整非空文案', () => {
    for (const copy of Object.values(SCENE_COPY)) {
      expect(copy.label.length).toBeGreaterThan(0)
      expect(copy.roomTitle.length).toBeGreaterThan(0)
      expect(copy.roomSlogan.length).toBeGreaterThan(0)
      expect(copy.sceneHint.length).toBeGreaterThan(0)
    }
  })
})
