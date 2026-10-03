/**
 * 生成自习室环境音资产（public/audio/rain.wav / snow.wav）。
 *
 * 为什么是程序化生成：本机无 Docker 也无 ffmpeg，上游 🅢 的 rain.mp3（28.8MB）/
 * snow.wav（7.2MB）直接入仓太重；音频本身是氛围噪声，用种子化噪声合成即可达到
 * 等效的陪伴感，且产出确定（同一种子 → 同一字节），可随时重生成。
 *
 * 循环无缝性：尾部 CUT_MS 与头部做等功率交叉淡化，拼接播放时无接缝爆音。
 * 重新生成：node scripts/generate-ambient-audio.mjs
 */

import { writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const SAMPLE_RATE = 22050
const DURATION_S = 45
const CUT_MS = 500 // 循环交叉淡化窗口
const FADE = Math.floor((SAMPLE_RATE * CUT_MS) / 1000)
const N = SAMPLE_RATE * DURATION_S

/** Mulberry32：种子化 PRNG，保证产物字节级确定 */
function mulberry32(seed) {
  let a = seed >>> 0
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 一阶低通 */
function lowpass(samples, alpha) {
  let y = 0
  return samples.map((x) => {
    y += alpha * (x - y)
    return y
  })
}

function normalize(samples, peak = 0.85) {
  const max = samples.reduce((m, x) => Math.max(m, Math.abs(x)), 0)
  if (max === 0) return samples
  const k = peak / max
  return samples.map((x) => x * k)
}

/** 雨声：棕噪声底盘（远雷/雨幕）+ 稀疏高频雨滴短促衰减 */
function rainSamples(seed) {
  const rand = mulberry32(seed)
  const white = Array.from({ length: N + FADE }, () => rand() * 2 - 1)
  // 棕噪声：白噪声积分后归一
  let acc = 0
  const brown = white.map((x) => {
    acc = (acc + 0.02 * x) / 1.02
    return acc * 3.5
  })
  const base = lowpass(brown, 0.25)

  // 雨滴：泊松式稀疏触发，2-6kHz 正弦短衰减，音量很低
  const drops = new Float64Array(N + FADE)
  let t = 0
  while (t < N + FADE) {
    t += 200 + rand() * 2400 // 采样点间隔，平均约每 60ms 一滴
    const freq = 2000 + rand() * 4000
    const dur = Math.floor(SAMPLE_RATE * (0.004 + rand() * 0.01))
    const amp = 0.05 + rand() * 0.1
    for (let i = 0; i < dur && t + i < N + FADE; i++) {
      const env = Math.exp((-3 * i) / dur)
      drops[t + i] += amp * env * Math.sin((2 * Math.PI * freq * i) / SAMPLE_RATE)
    }
  }
  return normalize(base.map((x, i) => x * 0.9 + drops[i]))
}

/** 风声：低通白噪声（风噪）+ 双正弦慢调制（阵风起伏） */
function snowSamples(seed) {
  const rand = mulberry32(seed)
  const white = Array.from({ length: N + FADE }, () => rand() * 2 - 1)
  const wind = lowpass(white, 0.045)
  return normalize(
    wind.map((x, i) => {
      const gust =
        0.7 +
        0.18 * Math.sin((2 * Math.PI * 0.07 * i) / SAMPLE_RATE) +
        0.12 * Math.sin((2 * Math.PI * 0.013 * i) / SAMPLE_RATE + 1.3)
      return x * gust
    }),
  )
}

/** 循环交叉淡化：输出前 FADE 点与尾段混合，消除接缝 */
function loopize(samples) {
  const out = new Float64Array(N)
  for (let i = 0; i < N; i++) {
    if (i < FADE) {
      const w = i / FADE
      out[i] = samples[i] * w + samples[N + i] * (1 - w)
    } else {
      out[i] = samples[i]
    }
  }
  return out
}

function toWav(samples) {
  const dataLen = N * 2
  const buf = Buffer.alloc(44 + dataLen)
  buf.write('RIFF', 0)
  buf.writeUInt32LE(36 + dataLen, 4)
  buf.write('WAVE', 8)
  buf.write('fmt ', 12)
  buf.writeUInt32LE(16, 16)
  buf.writeUInt16LE(1, 20) // PCM
  buf.writeUInt16LE(1, 22) // mono
  buf.writeUInt32LE(SAMPLE_RATE, 24)
  buf.writeUInt32LE(SAMPLE_RATE * 2, 28)
  buf.writeUInt16LE(2, 32)
  buf.writeUInt16LE(16, 34)
  buf.write('data', 36)
  for (let i = 0; i < N; i++) {
    buf.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767))), 44 + i * 2)
  }
  return buf
}

const outDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'audio')
mkdirSync(outDir, { recursive: true })
writeFileSync(join(outDir, 'rain.wav'), toWav(loopize(rainSamples(20261004))))
writeFileSync(join(outDir, 'snow.wav'), toWav(loopize(snowSamples(20261005))))
console.log(`written: ${outDir}/rain.wav, snow.wav (${DURATION_S}s @ ${SAMPLE_RATE}Hz mono 16-bit)`)
