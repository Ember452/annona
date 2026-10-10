/**
 * audio_chunk 串行播放队列（voice-adr 修订 1，P3-03 的客户端侧收口）。
 *
 * 后端一轮会按句序下发多个 audio_chunk（句级并发合成、按序发射、首句优先），浏览器必须
 * 一段播完再播下一段——同时 play 会叠音。队列排空且本轮收到 isLast 时上报 done，
 * 服务端据此立即解除回声窗（时间窗仅作无信号时的兜底）。
 *
 * 刻意保持纯逻辑：播放器与上报回调由注入方提供，不碰 DOM，vitest 直接钉行为
 * （voiceProtocol.ts 同款组织方式）。
 */

export interface AudioQueueDeps {
  /**
   * 播放一段 base64 WAV。<b>无论正常播完、被自动播放策略拦截还是解码失败，都必须回调
   * onEnded</b>——队列靠它推进，漏回调会把后续所有句子卡死。
   */
  playClip: (base64Wav: string, onEnded: () => void) => void
  /** 本轮音频已全部播完（对应上行 control audio_done）。 */
  onDrained: () => void
}

export class AudioChunkQueue {
  private readonly pending: string[] = []
  private playing = false
  /** 本轮是否已收到末块：没收到就排空说明还在路上，此时不能上报 done。 */
  private turnEnded = false
  private closed = false

  private readonly deps: AudioQueueDeps

  constructor(deps: AudioQueueDeps) {
    this.deps = deps
  }

  /**
   * 入队一个下行块。空 data 只有一种合法来源：末块标记（后端不为空句发空块），
   * 因此它只置 turnEnded，不进播放队列。
   */
  push(base64Wav: string, isLast: boolean): void {
    if (this.closed) {
      return
    }
    if (base64Wav !== '') {
      this.pending.push(base64Wav)
    }
    if (isLast) {
      this.turnEnded = true
    }
    this.pump()
  }

  /** 断连/卸载：停止推进并清空队列（在播的那一段由浏览器自然结束，其回调被忽略）。 */
  close(): void {
    this.closed = true
    this.pending.length = 0
    this.turnEnded = false
  }

  private pump(): void {
    if (this.playing || this.closed) {
      return
    }
    const next = this.pending.shift()
    if (next === undefined) {
      if (this.turnEnded) {
        this.turnEnded = false
        this.deps.onDrained()
      }
      return
    }
    this.playing = true
    this.deps.playClip(next, () => {
      if (this.closed) {
        return
      }
      this.playing = false
      this.pump()
    })
  }
}
