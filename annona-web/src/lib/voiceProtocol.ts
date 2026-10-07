/**
 * /ws/voice 帧协议与客户端状态归一（voice-adr §决策 5）。
 *
 * <p>本模块刻意保持<b>纯函数</b>：解析、事件归一（reducer）、URL 拼接都不碰
 * React/浏览器副作用，vitest 直接钉行为（🅜 audioTranscription.test.ts 的组织方式）。
 * 归一语义与后端 VoiceProtocol 一一对应；改协议两端同批改。
 */
import { API_BASE_URL } from '@/api/request'

/** 客户端上行帧（对齐后端 VoiceProtocol.ClientFrame）。 */
export interface VoiceClientFrame {
  type: 'start' | 'audio' | 'control' | 'submit' | 'ping'
  data?: string
  action?: 'pause' | 'resume' | 'stop'
  text?: string
  directionId?: string
}

/** 服务端下行事件（对齐后端 VoiceProtocol 各下行帧）。 */
export type VoiceServerEvent =
  | { type: 'ready' }
  | { type: 'subtitle'; text: string; isFinal: boolean }
  | { type: 'audio_chunk'; data: string; seq: number; isLast: boolean }
  | { type: 'text'; content: string }
  | { type: 'state'; status: 'ACTIVE' | 'PAUSED' | 'FINALIZED' }
  | { type: 'error'; code: number; message: string; recoverable: boolean }

/** 归一后的客户端视图状态（reducer 的产出物）。 */
export interface VoiceSessionState {
  asrReady: boolean
  status: 'ACTIVE' | 'PAUSED' | 'FINALIZED' | 'IDLE'
  /** 实时字幕草稿（partial；同句被后续 partial/final 覆盖）。 */
  partial: string | null
  /** 已定稿转写（final 与手动提交按时间序追加）。 */
  finals: string[]
  /** 面试官文本（开场白/降级字幕）。 */
  interviewerTexts: string[]
  /** 最近一个错误帧（UI 提示用；recoverable=false 时页面应禁用录音）。 */
  lastError: { code: number; message: string; recoverable: boolean } | null
}

export const initialVoiceState: VoiceSessionState = {
  asrReady: false,
  status: 'IDLE',
  partial: null,
  finals: [],
  interviewerTexts: [],
  lastError: null,
}

/**
 * 事件归一 reducer：把一个服务端事件折叠进客户端视图状态。
 * 语义：partial 覆盖草稿；final 追加并清草稿；FINALIZED 后不再接收转写（迟到丢弃）。
 */
export function reduceVoiceState(state: VoiceSessionState, event: VoiceServerEvent): VoiceSessionState {
  switch (event.type) {
    case 'ready':
      return { ...state, asrReady: true }
    case 'subtitle':
      // FINALIZED 后到达的定稿是迟到数据，丢弃（后端已守，前端双保险）
      if (state.status === 'FINALIZED') {
        return state
      }
      return event.isFinal
        ? { ...state, partial: null, finals: [...state.finals, event.text] }
        : { ...state, partial: event.text }
    case 'audio_chunk':
    case 'text':
      // 面试官文本（开场白或 TTS 降级字幕）；音频块由播放侧处理，不进 reducer
      return event.type === 'text'
        ? { ...state, interviewerTexts: [...state.interviewerTexts, event.content] }
        : state
    case 'state':
      return { ...state, status: event.status, partial: event.status === 'FINALIZED' ? null : state.partial }
    case 'error':
      return { ...state, lastError: { code: event.code, message: event.message, recoverable: event.recoverable } }
    default:
      return state
  }
}

/**
 * 解析服务端帧；非 JSON / 缺 type 返回 null（调用方静默忽略——协议要求坏帧由后端
 * 主动发 error 帧，前端解析失败只可能是网络截断）。
 */
export function parseServerFrame(raw: string): VoiceServerEvent | null {
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null || !('type' in parsed)) {
      return null
    }
    const event = parsed as VoiceServerEvent
    switch (event.type) {
      case 'ready':
      case 'subtitle':
      case 'audio_chunk':
      case 'text':
      case 'state':
      case 'error':
        return event
      default:
        return null
    }
  } catch {
    return null
  }
}

/** 上行帧序列化。 */
export function encodeClientFrame(frame: VoiceClientFrame): string {
  return JSON.stringify(frame)
}

/**
 * /ws/voice 的 WS 地址：API_BASE_URL（http/https）映射为 ws/wss；空串 = 同源直连
 * （生产 nginx 反代同源，开发 Vite 代理或直连后端端口）。
 */
export function voiceWebSocketUrl(): string {
  if (API_BASE_URL === '') {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    return `${scheme}://${window.location.host}/ws/voice`
  }
  const url = new URL(API_BASE_URL)
  const scheme = url.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${url.host}/ws/voice`
}
