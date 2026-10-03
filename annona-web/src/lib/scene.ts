/**
 * 场景元数据：纯类型 + 纯数据 + 纯函数（可安全被组件、hook 与测试共同引用）。
 *
 * 两套词汇的对应关系：
 *  - SceneType（rain/snow/cloud）是 CSS 侧词汇，对应 globals.css 的 [data-scene] 选择器；
 *  - ThemeKey（rainforest/snow/cloud）是持久化词汇，与后端 user_profile.theme_key
 *    （Flyway V1 默认 'rainforest'）对齐。
 * 新增场景需同步：SceneType / SCENE_COPY / globals.css 的 [data-scene] 块 /
 * SCENE_AUDIO_SRC（useAmbientSound）。
 */

export type SceneType = 'rain' | 'snow' | 'cloud'

/** 持久化到 user_profile.theme_key 的主题键（与 Flyway V1 的 DEFAULT 'rainforest' 对齐） */
export type ThemeKey = 'rainforest' | 'snow' | 'cloud'

export const SCENE_STORAGE_KEY = 'annona-scene'
export const DEFAULT_SCENE: SceneType = 'rain'

/** 宽松解析 localStorage 值；不认识的值一律回落默认场景（防止手改存储把主题打挂） */
export function asSceneType(value: string | null | undefined): SceneType {
  return value === 'snow' || value === 'cloud' ? value : DEFAULT_SCENE
}

/** 宽松解析后端 theme_key；空/不认识回落 'rainforest'（与 V1 列默认一致） */
export function asThemeKey(value: string | null | undefined): ThemeKey {
  return value === 'snow' || value === 'cloud' ? value : 'rainforest'
}

export function themeKeyToScene(key: ThemeKey): SceneType {
  return key === 'rainforest' ? 'rain' : key
}

export function sceneToThemeKey(scene: SceneType): ThemeKey {
  return scene === 'rain' ? 'rainforest' : scene
}

/** 场景文案：UI 上所有「雨林/雪日/暖云」字样从这里读，不散落硬编码 */
export interface SceneCopy {
  /** 窄空间显示名（tab/选择器） */
  label: string
  /** 自习室大标题 */
  roomTitle: string
  /** 标题下副文案 */
  roomSlogan: string
  /** 场景选择卡片的提示 */
  sceneHint: string
}

export const SCENE_COPY: Record<SceneType, SceneCopy> = {
  rain: {
    label: '雨林',
    roomTitle: '雨林自习室',
    roomSlogan: '在雨声中沉浸，让每一段专注都有节奏。',
    sceneHint: '雨声环境音',
  },
  snow: {
    label: '雪日',
    roomTitle: '雪日自习室',
    roomSlogan: '雪落无声，恰好容纳专注的心。',
    sceneHint: '落雪环境音',
  },
  cloud: {
    label: '暖云',
    roomTitle: '暖云自习室',
    roomSlogan: '云层之上，只有你和要做的事。',
    sceneHint: '静谧（无环境音）',
  },
}
