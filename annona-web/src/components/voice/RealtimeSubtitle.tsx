/**
 * 实时字幕区（P3-01）：partial 草稿 + 定稿流 + 面试官文本分色展示。
 * 纯展示组件，无状态——字幕语义见 voice-adr §决策 1（partial 可变、final 定稿）。
 */
interface RealtimeSubtitleProps {
  partial: string | null
  finals: string[]
  interviewerTexts: string[]
}

export default function RealtimeSubtitle({ partial, finals, interviewerTexts }: RealtimeSubtitleProps) {
  return (
    <div className="space-y-3" aria-live="polite">
      {interviewerTexts.length > 0 && (
        <div className="space-y-2">
          {interviewerTexts.map((content, index) => (
            <p
              key={`interviewer-${index}`}
              data-testid="interviewer-line"
              className="rounded-md bg-muted px-3 py-2 text-sm text-foreground"
            >
              面试官：{content}
            </p>
          ))}
        </div>
      )}
      {finals.length > 0 && (
        <div className="space-y-1">
          {finals.map((text, index) => (
            <p key={`final-${index}`} data-testid="final-line" className="text-sm text-foreground">
              {text}
            </p>
          ))}
        </div>
      )}
      {partial !== null && (
        <p data-testid="partial-line" className="text-sm text-muted-foreground">
          {partial}
          <span className="ml-1 animate-pulse">▍</span>
        </p>
      )}
      {partial === null && finals.length === 0 && interviewerTexts.length === 0 && (
        <p className="text-sm text-muted-foreground">字幕将在这里实时出现。</p>
      )}
    </div>
  )
}
