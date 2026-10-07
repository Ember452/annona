import { useState } from 'react'
import { Link } from 'react-router-dom'

import RealtimeSubtitle from '@/components/voice/RealtimeSubtitle'
import AudioRecorder from '@/components/voice/AudioRecorder'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { useVoiceSession } from '@/hooks/useVoiceSession'

/**
 * 语音面试页（P3-01 批 1 骨架）：WS 链路 + 流式字幕 + 开场白播报 + 手动作答。
 * 对话式追问与评估接入在批 2（P3_VOICE_PLAN）；本页即最小可复现的用户路径。
 */
export default function VoicePage() {
  const voice = useVoiceSession()
  const [recording, setRecording] = useState(false)
  const [manualText, setManualText] = useState('')

  const status = voice.state.status
  const started = status !== 'IDLE'
  const finalized = status === 'FINALIZED'

  return (
    <section className="mx-auto w-full max-w-3xl space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="font-heading text-2xl font-semibold tracking-tight">语音面试 · 内测</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            全双工语音链路骨架（批 1）：说话即出字幕，开场白自动播报；追问与评估在后续批次。
            建议佩戴耳机；外放场景请用手动提交。
          </p>
        </div>
        <Link
          to="/interview"
          className="text-sm text-muted-foreground underline underline-offset-4 hover:text-foreground"
        >
          返回文字面试
        </Link>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">
            会话状态：{voice.connected ? '已连接' : '未连接'} · {status}
            {voice.state.asrReady ? ' · 识别就绪' : ''}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {voice.playbackBlocked && (
            <Button size="sm" variant="outline" onClick={voice.unlockPlayback} data-testid="unlock-playback">
              点击解锁音频播放
            </Button>
          )}
          {voice.state.lastError !== null && (
            <p className="text-xs text-destructive" data-testid="voice-error">
              错误 {voice.state.lastError.code}：{voice.state.lastError.message}
            </p>
          )}

          <div className="flex flex-col items-center gap-3 py-2">
            <AudioRecorder
              isRecording={recording}
              disabled={!voice.connected || !voice.state.asrReady || finalized || status === 'PAUSED'}
              onRecordingChange={setRecording}
              onAudioData={voice.sendAudio}
            />
            <div className="flex flex-wrap items-center justify-center gap-2">
              {!started && (
                <Button onClick={() => voice.start()} data-testid="voice-start" disabled={!voice.connected}>
                  开始会话
                </Button>
              )}
              {started && !finalized && (
                <>
                  {status === 'PAUSED' ? (
                    <Button size="sm" onClick={voice.resume}>
                      继续
                    </Button>
                  ) : (
                    <Button size="sm" variant="outline" onClick={voice.pause}>
                      暂停
                    </Button>
                  )}
                  <Button size="sm" variant="destructive" onClick={voice.stop} data-testid="voice-stop">
                    结束会话
                  </Button>
                </>
              )}
            </div>
          </div>

          <RealtimeSubtitle
            partial={voice.state.partial}
            finals={voice.state.finals}
            interviewerTexts={voice.state.interviewerTexts}
          />

          <form
            className="flex gap-2"
            onSubmit={(event) => {
              event.preventDefault()
              const text = manualText.trim()
              if (text === '' || !started || finalized) {
                return
              }
              voice.submitText(text)
              setManualText('')
            }}
          >
            <Input
              value={manualText}
              onChange={(event) => setManualText(event.target.value)}
              placeholder="手动作答（ASR 不可用或外放场景）"
              disabled={!started || finalized}
              data-testid="manual-input"
            />
            <Button type="submit" variant="secondary" disabled={!started || finalized}>
              提交
            </Button>
          </form>
        </CardContent>
      </Card>
    </section>
  )
}
