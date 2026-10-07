import { useEffect, useRef, useState } from 'react'
import { Mic, MicOff } from 'lucide-react'

/**
 * 麦克风采集按钮（P3-01，🅖 AudioRecorder 同构、去 MicVAD——断句权威在服务端 VAD，
 * voice-adr §决策 5 否决了客户端 VAD 的 CDN 依赖）。
 *
 * 调用约束：{@link onAudioData} 每帧回调 200ms/16kHz Int16 PCM 的 base64；组件在卸载与
 * disabled 时自清理媒体资源；startingRef 防并发启动，mountedRef 防卸载后回调。
 */
interface AudioRecorderProps {
  isRecording: boolean
  disabled?: boolean
  onRecordingChange: (isRecording: boolean) => void
  onAudioData: (audioDataBase64: string) => void
}

const TARGET_SAMPLE_RATE = 16000

export default function AudioRecorder({
  isRecording,
  disabled = false,
  onRecordingChange,
  onAudioData,
}: AudioRecorderProps) {
  const [volume, setVolume] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const mediaStreamRef = useRef<MediaStream | null>(null)
  const audioContextRef = useRef<AudioContext | null>(null)
  const workletNodeRef = useRef<AudioWorkletNode | null>(null)
  const gainNodeRef = useRef<GainNode | null>(null)
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const mountedRef = useRef(true)
  const recordingActiveRef = useRef(false)
  const startingRef = useRef(false)

  const cleanupRecordingResources = (updateVolume = true) => {
    recordingActiveRef.current = false
    if (intervalRef.current) {
      clearInterval(intervalRef.current)
      intervalRef.current = null
    }
    if (workletNodeRef.current) {
      workletNodeRef.current.port.onmessage = null
      workletNodeRef.current.disconnect()
      workletNodeRef.current = null
    }
    if (gainNodeRef.current) {
      gainNodeRef.current.disconnect()
      gainNodeRef.current = null
    }
    if (mediaStreamRef.current) {
      mediaStreamRef.current.getTracks().forEach((track) => track.stop())
      mediaStreamRef.current = null
    }
    if (audioContextRef.current) {
      void audioContextRef.current.close()
      audioContextRef.current = null
    }
    if (updateVolume) {
      setVolume(0)
    }
  }

  /** Int16 PCM ArrayBuffer → base64（分块 fromCharCode 防栈溢出，🅖 同款）。 */
  const arrayBufferToBase64 = (buffer: ArrayBuffer): string => {
    const bytes = new Uint8Array(buffer)
    let binary = ''
    const chunkSize = 0x8000
    for (let i = 0; i < bytes.length; i += chunkSize) {
      const chunk = bytes.subarray(i, i + chunkSize)
      binary += String.fromCharCode(...chunk)
    }
    return btoa(binary)
  }

  const startRecording = async () => {
    if (startingRef.current) {
      return
    }
    startingRef.current = true
    setError(null)
    try {
      if (!window.AudioContext) {
        throw new Error('当前浏览器不支持 AudioWorklet，请使用新版 Chrome/Edge')
      }
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          sampleRate: TARGET_SAMPLE_RATE,
        },
      })
      if (!mountedRef.current) {
        stream.getTracks().forEach((track) => track.stop())
        startingRef.current = false
        return
      }
      mediaStreamRef.current = stream

      const audioContext = new AudioContext({ sampleRate: TARGET_SAMPLE_RATE })
      if (!audioContext.audioWorklet) {
        void audioContext.close()
        throw new Error('当前浏览器不支持 AudioWorklet，请使用新版 Chrome/Edge')
      }
      const source = audioContext.createMediaStreamSource(stream)

      // 音量监视（按钮涟漪反馈）
      const analyser = audioContext.createAnalyser()
      analyser.fftSize = 256
      source.connect(analyser)
      const dataArray = new Uint8Array(analyser.frequencyBinCount)
      intervalRef.current = setInterval(() => {
        if (!mountedRef.current || !analyser) {
          return
        }
        analyser.getByteFrequencyData(dataArray)
        const average = dataArray.reduce((a, b) => a + b) / dataArray.length
        setVolume(average)
      }, 100)

      await audioContext.audioWorklet.addModule('/audio-worklet/pcm-processor.js')
      if (!mountedRef.current) {
        cleanupRecordingResources(false)
        startingRef.current = false
        return
      }

      const workletNode = new AudioWorkletNode(audioContext, 'pcm-processor')
      workletNodeRef.current = workletNode
      workletNode.port.onmessage = (event) => {
        if (!mountedRef.current || !recordingActiveRef.current) {
          return
        }
        onAudioData(arrayBufferToBase64(event.data as ArrayBuffer))
      }

      // 输出置零接 destination：AudioWorklet 节点必须连到 Consumer 才会被拉流
      const gainNode = audioContext.createGain()
      gainNode.gain.value = 0
      gainNodeRef.current = gainNode
      source.connect(workletNode)
      workletNode.connect(gainNode)
      gainNode.connect(audioContext.destination)

      audioContextRef.current = audioContext
      recordingActiveRef.current = true
      startingRef.current = false
      if (mountedRef.current) {
        onRecordingChange(true)
      }
    } catch (e) {
      startingRef.current = false
      cleanupRecordingResources(mountedRef.current)
      if (!mountedRef.current) {
        return
      }
      const message = e instanceof Error ? e.message : '无法访问麦克风，请检查权限设置'
      setError(message)
    }
  }

  const stopRecording = () => {
    startingRef.current = false
    cleanupRecordingResources(mountedRef.current)
    if (isRecording) {
      onRecordingChange(false)
    }
  }

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      stopRecording()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if ((disabled || !isRecording) && recordingActiveRef.current) {
      cleanupRecordingResources(mountedRef.current)
      if (isRecording) {
        onRecordingChange(false)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [disabled, isRecording])

  const toggleRecording = () => {
    if (disabled && !isRecording) {
      return
    }
    if (isRecording) {
      stopRecording()
    } else {
      void startRecording()
    }
  }

  return (
    <div className="flex flex-col items-center gap-2">
      <div className="relative flex items-center justify-center">
        {isRecording && (
          <div
            aria-hidden
            className="pointer-events-none absolute rounded-full border border-primary-500/50 transition-all duration-75"
            style={{
              width: `${100 + (volume / 255) * 100}%`,
              height: `${100 + (volume / 255) * 100}%`,
              opacity: Math.max(0, 1 - (volume / 255) * 1.5),
            }}
          />
        )}
        <button
          type="button"
          data-testid="voice-recorder-toggle"
          onClick={toggleRecording}
          disabled={disabled && !isRecording}
          className={`relative z-10 flex h-16 w-16 items-center justify-center rounded-full shadow-xl transition-all duration-300 ${
            disabled && !isRecording
              ? 'cursor-not-allowed opacity-50 shadow-none'
              : isRecording
                ? 'bg-primary-500 shadow-primary-500/40 hover:bg-primary-600'
                : 'bg-slate-700 shadow-slate-900/50 hover:bg-slate-600'
          }`}
          title={isRecording ? '停止说话' : '开始说话'}
        >
          {isRecording ? (
            <Mic className="h-7 w-7 text-white" />
          ) : (
            <MicOff className="h-7 w-7 text-slate-300" />
          )}
        </button>
      </div>
      {error !== null && <p className="text-xs text-destructive">{error}</p>}
    </div>
  )
}
