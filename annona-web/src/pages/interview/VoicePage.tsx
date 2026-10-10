import { useState } from 'react'
import { Link } from 'react-router-dom'

import RealtimeSubtitle from '@/components/voice/RealtimeSubtitle'
import AudioRecorder from '@/components/voice/AudioRecorder'
import DirectionSelector from '@/components/direction/DirectionSelector'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { useVoiceSession } from '@/hooks/useVoiceSession'
import type { Direction } from '@/types/direction'

/**
 * 语音面试页（P3-01/03）：WS 链路 + 实时字幕 + 开场白播报 + 题库驱动的逐题对话轮。
 * 方向决定题目队列（无方向/题库空 → 自由问答，不进评分）；音频作答靠「回答完毕」
 * 收本轮，文字作答靠下方提交（P3 出口③的降级路径）。
 */
export default function VoicePage() {
  const voice = useVoiceSession()
  const [direction, setDirection] = useState<Direction | null>(null)
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
            逐题语音面试：选方向后面试官按题库逐题提问，说完点「回答完毕」。
            建议佩戴耳机；外放或识别不可用时用文字提交。
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
                <>
                  <DirectionSelector value={direction} onChange={setDirection} />
                  <Button
                    onClick={() => voice.start(direction?.id)}
                    disabled={!voice.connected}
                    data-testid="voice-start"
                  >
                    开始会话
                  </Button>
                </>
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
                  <Button size="sm" onClick={voice.finishAnswer} data-testid="voice-answer-done">
                    回答完毕
                  </Button>
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
