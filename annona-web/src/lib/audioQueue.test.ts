import { describe, expect, it } from 'vitest'
import { AudioChunkQueue } from './audioQueue'

/**
 * 串行播放队列规格（voice-adr 修订 1，P3-03）：一轮多句必须一句播完再播下一句，
 * 末块（isLast）播完才上报 done。堆叠播放会把 TTS 句子混成一团噪音。
 */
describe('AudioChunkQueue', () => {
  /** 可手工推进的假播放器：记录播放序，由测试决定何时算播完。 */
  function harness() {
    const played: string[] = []
    const ended: (() => void)[] = []
    const drained: number[] = []
    let clock = 0
    const queue = new AudioChunkQueue({
      playClip: (data, onEnded) => {
        played.push(data)
        ended.push(onEnded)
      },
      onDrained: () => {
        clock += 1
        drained.push(clock)
      },
    })
    /** 推进：把最早那段标记为播完。 */
    const finishPlaying = () => {
      const onEnded = ended.shift()
      expect(onEnded, '队列在没有在播内容时不应回调 onEnded').toBeDefined()
      onEnded?.()
    }
    return { queue, played, drained, finishPlaying }
  }

  it('三句串行播放，done 在最后一句播完后恰好一次', () => {
    const { queue, played, drained, finishPlaying } = harness()

    queue.push('第一句', false)
    queue.push('第二句', false)
    queue.push('第三句', true)

    expect(played).toEqual(['第一句'])
    expect(drained).toEqual([])

    finishPlaying()
    finishPlaying()
    finishPlaying()

    expect(played).toEqual(['第一句', '第二句', '第三句'])
    expect(drained).toHaveLength(1)
  })

  it('未收到 isLast 前排空不上报 done；末块到达即补报', () => {
    const { queue, drained, finishPlaying } = harness()

    queue.push('句子', false)
    finishPlaying()

    expect(drained).toEqual([])

    // 后端排空末块是「空 data + isLast」，不进播放队列
    queue.push('', true)

    expect(drained).toHaveLength(1)
  })

  it('播放被拦截时 onEnded 仍回调，队列不卡死', () => {
    const played: string[] = []
    const drained: string[] = []
    const queue = new AudioChunkQueue({
      // 模拟自动播放被策略拦截：不同步失败，而是立即结束（hook 侧的接法）
      playClip: (data, onEnded) => {
        played.push(data)
        onEnded()
      },
      onDrained: () => drained.push('done'),
    })

    queue.push('A', false)
    queue.push('B', true)

    expect(played).toEqual(['A', 'B'])
    expect(drained).toEqual(['done'])
  })

  it('close 后不再播放也不上报 done', () => {
    const { queue, played, drained, finishPlaying } = harness()

    queue.push('A', false)
    finishPlaying()
    queue.close()
    queue.push('B', true)
    queue.push('C', false)

    expect(played).toEqual(['A'])
    expect(drained).toEqual([])
  })
})
