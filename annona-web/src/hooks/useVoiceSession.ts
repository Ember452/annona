import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'

import {
  encodeClientFrame,
  initialVoiceState,
  parseServerFrame,
  reduceVoiceState,
  voiceWebSocketUrl,
  type VoiceSessionState,
} from '@/lib/voiceProtocol'

/**
 * /ws/voice 会话 hook（P3-01，voice-adr §决策 5）。
 *
 * 副作用与调用约束：
 * - {@link start} 必须在用户手势回调里调用（麦克风权限 + 浏览器自动播放解锁都依赖手势链）；
 * - WS 断连自动重连 3 次 × 2s（弱网恢复口径，P3-01 验收）；重连成功后需重新 start；
 * - audio_chunk 播放经 {@code <Audio>}（data URL），被浏览器自动播放策略拦截时置
 *   {@link playbackBlocked}，由 UI 提示用户点击（🅢 手势解锁模式）；
 * - 卸载即断连；组件不得在 FINALIZED 后继续 sendAudio（后端会静默丢弃）。
 */
export interface UseVoiceSessionResult {
  state: VoiceSessionState
  /** WS 是否处于连接态（重连中为 false）。 */
  connected: boolean
  /** 浏览器拦截了音频自动播放：提示用户点击页面后重试。 */
  playbackBlocked: boolean
  /** 手势点：解锁播放（播放被拦截后由 UI 调用）。 */
  unlockPlayback: () => void
  start: (directionId?: string) => void
  pause: () => void
  resume: () => void
  stop: () => void
  submitText: (text: string) => void
  sendAudio: (base64Pcm: string) => void
}

export function useVoiceSession(): UseVoiceSessionResult {
  const [state, dispatch] = useReducer(reduceVoiceState, initialVoiceState)
  const [connected, setConnected] = useState(false)
  const [playbackBlocked, setPlaybackBlocked] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const retriesRef = useRef(0)
  /** 由 start 记录 directionId，重连后自动重开（弱网恢复）。 */
  const lastDirectionRef = useRef<string | undefined>(undefined)

  const send = useCallback((frame: Parameters<typeof encodeClientFrame>[0]) => {
    const ws = wsRef.current
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(encodeClientFrame(frame))
    }
  }, [])

  const connect = useCallback((): Promise<WebSocket> => {
    // 不 reject：失败由 onclose 的重连逻辑承接（3 次后放弃，UI 以 connected=false 呈现）
    return new Promise((resolve) => {
      const ws = new WebSocket(voiceWebSocketUrl())
      ws.onopen = () => {
        retriesRef.current = 0
        setConnected(true)
        resolve(ws)
      }
      ws.onerror = () => {
        // onclose 会跟在 onerror 后，重连逻辑统一在 onclose 里做
      }
      ws.onclose = () => {
        setConnected(false)
        // 用户主动 stop 后服务端会正常关连接，此时不再重连（避免死循环）
        if (retriesRef.current < 3) {
          retriesRef.current += 1
          setTimeout(() => {
            connect()
              .then((reconnected) => {
                // 重连成功即重开会话（服务端旧会话已 ABANDONED）
                if (lastDirectionRef.current !== undefined) {
                  send({ type: 'start', directionId: lastDirectionRef.current })
                } else {
                  send({ type: 'start' })
                }
                void reconnected
              })
              .catch(() => undefined)
          }, 2000)
        }
      }
      ws.onmessage = (message) => {
        const event = parseServerFrame(message.data)
        if (event === null) {
          return
        }
        if (event.type === 'audio_chunk') {
          playAudioChunk(event.data)
        }
        dispatch(event)
      }
      wsRef.current = ws
    })
    // send 在闭包里被 onmessage/onclose 引用，依赖不随渲染变化（useCallback 空依赖）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const playAudioChunk = useCallback((base64Wav: string) => {
    const audio = new Audio(`data:audio/wav;base64,${base64Wav}`)
    audio.play().catch(() => {
      // 自动播放被策略拦截：不吞掉，交 UI 引导用户手势解锁
      setPlaybackBlocked(true)
    })
  }, [])

  const start = useCallback(
    (directionId?: string) => {
      lastDirectionRef.current = directionId
      setPlaybackBlocked(false)
      const existing = wsRef.current
      if (existing && existing.readyState === WebSocket.OPEN) {
        send({ type: 'start', directionId })
        return
      }
      connect()
        .then((ws) => {
          ws.send(encodeClientFrame({ type: 'start', directionId }))
        })
        .catch(() => undefined)
    },
    [connect, send],
  )

  const unlockPlayback = useCallback(() => {
    setPlaybackBlocked(false)
    // 播放一段极短静音完成解锁（🅢/🅜 手势解锁模式：策略只信任手势链内的首次 play）
    const primer = new Audio(
      'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=',
    )
    primer.play().catch(() => undefined)
  }, [])

  useEffect(
    () => () => {
      const ws = wsRef.current
      if (ws) {
        // 卸载不触发重连：先摘回调再关
        ws.onclose = null
        ws.close()
        wsRef.current = null
        setConnected(false)
      }
    },
    [],
  )

  return useMemo(
    () => ({
      state,
      connected,
      playbackBlocked,
      unlockPlayback,
      start,
      pause: () => send({ type: 'control', action: 'pause' }),
      resume: () => send({ type: 'control', action: 'resume' }),
      stop: () => send({ type: 'control', action: 'stop' }),
      submitText: (text: string) => send({ type: 'submit', text }),
      sendAudio: (base64Pcm: string) => send({ type: 'audio', data: base64Pcm }),
    }),
    [state, connected, playbackBlocked, unlockPlayback, start, send],
  )
}
