import { useEffect, useRef, useState } from 'react'

import type { SceneType } from '@/lib/scene'

/** 场景 → 环境音资源；没有专属音频的场景为 null（暖云=静谧），播放时静默。
 *  资产由 scripts/generate-ambient-audio.mjs 程序化生成（本机无 ffmpeg）。 */
const SCENE_AUDIO_SRC: Record<SceneType, string | null> = {
  rain: '/audio/rain.wav',
  snow: '/audio/snow.wav',
  cloud: null,
}

export const DEFAULT_AMBIENT_VOLUME = 0.3

/**
 * 浏览器自动播放策略拦截时，注册一次性首次交互重试；
 * 返回清理函数，供 effect 卸载时解绑（避免 resize 场景下监听器泄漏）。
 */
function playWithUserGestureFallback(audio: HTMLAudioElement): () => void {
  const resume = () => {
    audio.play().catch(() => {})
    document.removeEventListener('click', resume)
    document.removeEventListener('keydown', resume)
  }
  audio.play().catch(() => {
    document.addEventListener('click', resume)
    document.addEventListener('keydown', resume)
  })
  return () => {
    document.removeEventListener('click', resume)
    document.removeEventListener('keydown', resume)
  }
}

/**
 * 环境音控制（无 UI）：单例 Audio、循环播放、场景切换换 src 且保持播放连续性、
 * 切走标签页自动暂停/回来恢复（环境音不该在用户离开时继续响）。
 * 音频资产与 UI 开关分离——沉浸模式（P2-03）与设置页各自消费本 hook。
 */
export function useAmbientSound(scene: SceneType) {
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const cleanupFallbackRef = useRef<(() => void) | null>(null)
  const [volume, setVolumeState] = useState(DEFAULT_AMBIENT_VOLUME)
  // 用 effect 镜像最新音量，供事件回调读取（不在 render 期写 ref）
  const volumeRef = useRef(DEFAULT_AMBIENT_VOLUME)
  // 记录「离开页面前是否在播放」，回到页面时据此恢复
  const wasPlayingRef = useRef(false)

  useEffect(() => {
    volumeRef.current = volume
  }, [volume])

  // 1) 创建音频实例（仅一次）
  useEffect(() => {
    const audio = new Audio()
    audio.loop = true
    audio.volume = volumeRef.current
    audio.preload = 'auto'
    audioRef.current = audio
    return () => {
      cleanupFallbackRef.current?.()
      audio.pause()
      audio.src = ''
      audioRef.current = null
    }
  }, [])

  // 2) 场景变化时切换 src，保持音量与播放连续性
  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    const nextSrc = SCENE_AUDIO_SRC[scene]

    // 新场景没有音频：暂停并清空
    if (!nextSrc) {
      audio.pause()
      cleanupFallbackRef.current?.()
      cleanupFallbackRef.current = null
      if (audio.src) audio.src = ''
      return
    }

    const wasPlaying = !audio.paused && !audio.ended && !!audio.src

    // 同一份 src 不重复加载（防初始化或快速来回切换抖动）
    const absoluteNext = new URL(nextSrc, window.location.origin).href
    if (audio.src === absoluteNext) {
      if (wasPlaying) return
      cleanupFallbackRef.current?.()
      cleanupFallbackRef.current = playWithUserGestureFallback(audio)
      return
    }

    audio.src = nextSrc
    audio.load()
    cleanupFallbackRef.current?.()
    cleanupFallbackRef.current = playWithUserGestureFallback(audio)
  }, [scene])

  // 3) 切走标签页暂停、回来恢复
  useEffect(() => {
    const onVisibility = () => {
      const audio = audioRef.current
      if (!audio) return
      if (document.hidden) {
        wasPlayingRef.current = !audio.paused && !audio.ended && !!audio.src
        if (wasPlayingRef.current) audio.pause()
      } else if (wasPlayingRef.current) {
        wasPlayingRef.current = false
        audio.play().catch(() => {})
      }
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      wasPlayingRef.current = false
    }
  }, [])

  const setVolume = (v: number) => {
    const clamped = Math.max(0, Math.min(1, v))
    setVolumeState(clamped)
    if (audioRef.current) {
      audioRef.current.volume = clamped
    }
  }

  return { volume, setVolume }
}
