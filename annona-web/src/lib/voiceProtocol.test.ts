import { describe, expect, it } from 'vitest'
import {
  encodeClientFrame,
  initialVoiceState,
  parseServerFrame,
  reduceVoiceState,
  voiceWebSocketUrl,
} from './voiceProtocol'

/**
 * /ws/voice 协议与归一行为规格（批 1，voice-adr §决策 5）。
 * 语义与后端 VoiceProtocol/VoiceWebSocketHandler 一一对应——改协议两端同批改。
 */
describe('voiceProtocol', () => {
  describe('parseServerFrame', () => {
    it('六种下行帧都能解析', () => {
      expect(parseServerFrame('{"type":"ready"}')).toEqual({ type: 'ready' })
      expect(parseServerFrame('{"type":"subtitle","text":"你好","isFinal":false}')).toEqual({
        type: 'subtitle',
        text: '你好',
        isFinal: false,
      })
      expect(
        parseServerFrame('{"type":"audio_chunk","data":"QUJD","seq":1,"isLast":true}'),
      ).toEqual({ type: 'audio_chunk', data: 'QUJD', seq: 1, isLast: true })
      expect(parseServerFrame('{"type":"text","content":"开场白"}')).toEqual({
        type: 'text',
        content: '开场白',
      })
      expect(parseServerFrame('{"type":"state","status":"PAUSED"}')).toEqual({
        type: 'state',
        status: 'PAUSED',
      })
      expect(parseServerFrame('{"type":"error","code":2602,"message":"x","recoverable":true}')).toEqual({
        type: 'error',
        code: 2602,
        message: 'x',
        recoverable: true,
      })
    })

    it('非 JSON / 缺 type / 未知 type 返回 null（静默忽略，不抛）', () => {
      expect(parseServerFrame('not-json')).toBeNull()
      expect(parseServerFrame('{"noType":1}')).toBeNull()
      expect(parseServerFrame('{"type":"mystery"}')).toBeNull()
    })
  })

  describe('reduceVoiceState', () => {
    it('partial 覆盖草稿；final 追加并清草稿', () => {
      let state = initialVoiceState
      state = reduceVoiceState(state, { type: 'subtitle', text: '正在识别', isFinal: false })
      expect(state.partial).toBe('正在识别')
      state = reduceVoiceState(state, { type: 'subtitle', text: '正在识别中', isFinal: false })
      expect(state.partial).toBe('正在识别中')
      state = reduceVoiceState(state, { type: 'subtitle', text: '这是定稿', isFinal: true })
      expect(state.partial).toBeNull()
      expect(state.finals).toEqual(['这是定稿'])
    })

    it('FINALIZED 后到达的转写被丢弃（迟到数据双保险）', () => {
      let state = reduceVoiceState(initialVoiceState, { type: 'state', status: 'FINALIZED' })
      state = reduceVoiceState(state, { type: 'subtitle', text: '迟到的转写', isFinal: true })
      expect(state.finals).toEqual([])
      expect(state.partial).toBeNull()
    })

    it('text 事件追加面试官文本；audio_chunk 不进 reducer（播放侧处理）', () => {
      let state = reduceVoiceState(initialVoiceState, { type: 'text', content: '开场白' })
      expect(state.interviewerTexts).toEqual(['开场白'])
      const before = state
      state = reduceVoiceState(state, { type: 'audio_chunk', data: 'QUJD', seq: 1, isLast: true })
      expect(state).toBe(before)
    })

    it('state 事件驱动状态位；error 记录最近错误', () => {
      let state = reduceVoiceState(initialVoiceState, { type: 'state', status: 'PAUSED' })
      expect(state.status).toBe('PAUSED')
      state = reduceVoiceState(state, { type: 'error', code: 2602, message: '中断', recoverable: true })
      expect(state.lastError).toEqual({ code: 2602, message: '中断', recoverable: true })
    })
  })

  describe('encodeClientFrame', () => {
    it('上行帧字段按需序列化（audio 帧带 data，control 帧带 action）', () => {
      expect(JSON.parse(encodeClientFrame({ type: 'audio', data: 'QUJD' }))).toEqual({
        type: 'audio',
        data: 'QUJD',
      })
      expect(JSON.parse(encodeClientFrame({ type: 'control', action: 'pause' }))).toEqual({
        type: 'control',
        action: 'pause',
      })
      expect(JSON.parse(encodeClientFrame({ type: 'submit', text: '手动作答' }))).toEqual({
        type: 'submit',
        text: '手动作答',
      })
    })
  })

  describe('voiceWebSocketUrl', () => {
    it('空 API_BASE_URL 映射为同源 ws 地址', () => {
      // lib 模块在空 API_BASE_URL 下走 window.location；jsdom 默认 http://localhost/
      expect(voiceWebSocketUrl()).toBe('ws://localhost:3000/ws/voice')
    })
  })
})
