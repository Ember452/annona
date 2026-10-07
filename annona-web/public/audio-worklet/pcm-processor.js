/**
 * AudioWorkletProcessor：麦克风 PCM 采集（annona 语音链路上行口径，voice-adr §决策 5）。
 *
 * 职责（🅖 pcm-processor 同构）：
 * - 接收麦克风 Float32 输入
 * - 线性插值重采样到 16kHz（服务端 ASR 契约：PCM 16kHz 16bit 单声道）
 * - Float32 → Int16 PCM，攒满 200ms（3200 样本）postMessage 一帧
 *
 * 主线程通过 node.port.onmessage 接收 ArrayBuffer（Int16 PCM），自行转 base64 上行。
 */
class PcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.targetSampleRate = 16000
    this.pending = []
    this.pendingLength = 0
    this.samplesPerChunk = 3200 // 200ms @16kHz
  }

  /**
   * @param {Float32Array[]} inputs
   * @returns {boolean}
   */
  process(inputs) {
    const input = inputs[0]?.[0]
    if (!input || input.length === 0) {
      return true
    }
    const resampled = this.resample(input, sampleRate, this.targetSampleRate)
    const pcm = this.float32ToInt16(resampled)
    this.enqueue(pcm)
    this.flushChunks()
    return true
  }

  /**
   * 线性插值重采样
   * @param {Float32Array} input
   * @param {number} sourceRate
   * @param {number} targetRate
   * @returns {Float32Array}
   */
  resample(input, sourceRate, targetRate) {
    if (sourceRate === targetRate) {
      return input
    }
    const ratio = sourceRate / targetRate
    const outputLength = Math.max(1, Math.round(input.length / ratio))
    const output = new Float32Array(outputLength)
    for (let i = 0; i < outputLength; i++) {
      const sourceIndex = i * ratio
      const lower = Math.floor(sourceIndex)
      const upper = Math.min(lower + 1, input.length - 1)
      const weight = sourceIndex - lower
      output[i] = input[lower] * (1 - weight) + input[upper] * weight
    }
    return output
  }

  /**
   * Float32 → Int16 PCM
   * @param {Float32Array} input
   * @returns {Int16Array}
   */
  float32ToInt16(input) {
    const output = new Int16Array(input.length)
    for (let i = 0; i < input.length; i++) {
      const s = Math.max(-1, Math.min(1, input[i]))
      output[i] = s < 0 ? s * 0x8000 : s * 0x7fff
    }
    return output
  }

  /**
   * PCM 入队
   * @param {Int16Array} pcm
   */
  enqueue(pcm) {
    this.pending.push(pcm)
    this.pendingLength += pcm.length
  }

  /** 攒满 200ms 即切帧下发（clone 模式，避免 transferable 与宿主生命周期冲突）。 */
  flushChunks() {
    while (this.pendingLength >= this.samplesPerChunk && this.pending.length > 0) {
      const chunk = new Int16Array(this.samplesPerChunk)
      let offset = 0
      while (offset < this.samplesPerChunk && this.pending.length > 0) {
        const head = this.pending[0]
        if (!head || head.length === 0) {
          this.pending.shift()
          continue
        }
        const take = Math.min(head.length, this.samplesPerChunk - offset)
        chunk.set(head.subarray(0, take), offset)
        if (take === head.length) {
          this.pending.shift()
        } else {
          this.pending[0] = head.subarray(take)
        }
        offset += take
        this.pendingLength -= take
      }
      try {
        this.port.postMessage(chunk.buffer)
      } catch {
        // 卸载期间的 postMessage 失败静默忽略
      }
    }
    if (this.pending.length === 0) {
      this.pendingLength = 0
    }
  }
}

registerProcessor('pcm-processor', PcmProcessor)
