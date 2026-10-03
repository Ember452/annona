import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import {
  asSceneType,
  DEFAULT_SCENE,
  SCENE_COPY,
  SCENE_STORAGE_KEY,
  type SceneCopy,
  type SceneType,
} from '@/lib/scene'

export type { SceneType, SceneCopy }
export { SCENE_COPY, asSceneType }

interface SceneContextValue {
  scene: SceneType
  setScene: (scene: SceneType) => void
  /** 当前场景文案（scene 变化自动跟随） */
  copy: SceneCopy
}

const SceneContext = createContext<SceneContextValue | undefined>(undefined)

/** 把场景写到 html[data-scene]，globals.css 的场景变量选择器靠它生效 */
function applySceneToDOM(scene: SceneType) {
  const root = document.documentElement
  if (root.dataset.scene !== scene) {
    root.dataset.scene = scene
  }
}

/**
 * 全局场景 provider：localStorage 持久化 + html[data-scene] 同步。
 * index.html 静态写死 data-scene="rain" 作为首帧默认，挂载后这里接管；
 * 登录后的后端 theme_key 回填在设置页完成（P2-07），本 provider 只管本地态。
 */
export function SceneProvider({ children }: { children: ReactNode }) {
  const [scene, setSceneState] = useState<SceneType>(DEFAULT_SCENE)

  useEffect(() => {
    const saved = window.localStorage.getItem(SCENE_STORAGE_KEY)
    const initial = asSceneType(saved)
    setSceneState(initial)
    applySceneToDOM(initial)
  }, [])

  const setScene = (next: SceneType) => {
    setSceneState(next)
    applySceneToDOM(next)
    window.localStorage.setItem(SCENE_STORAGE_KEY, next)
  }

  const copy = useMemo(() => SCENE_COPY[scene], [scene])

  return <SceneContext.Provider value={{ scene, setScene, copy }}>{children}</SceneContext.Provider>
}

export function useScene() {
  const ctx = useContext(SceneContext)
  if (!ctx) {
    throw new Error('useScene must be used within a SceneProvider')
  }
  return ctx
}
