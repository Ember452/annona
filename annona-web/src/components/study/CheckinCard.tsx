import { useEffect, useState } from 'react'

import { studyApi } from '@/api/study'
import DirectionSelector from '@/components/direction/DirectionSelector'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useDirections } from '@/hooks/useDirections'
import { cn } from '@/lib/utils'
import type { Direction } from '@/types/direction'
import type { Checkin } from '@/types/study'

/**
 * 每日打卡卡（借鉴 🅢 checkin 的"一天一条"心智，拒绝语义改为幂等更新）：
 * 挂载即拉今日打卡回填（已打过则可改）；hours>0 由后端联动落 SELF_REPORTED 会话，
 * hours 归 0 即收回补录。截图上传（snapshotUrl）属后续阶段，先不提供入口。
 */
export default function CheckinCard() {
  const [direction, setDirection] = useState<Direction | null>(null)
  const [hours, setHours] = useState('0')
  const [mood, setMood] = useState('')
  const [energy, setEnergy] = useState<number | null>(null)
  const [note, setNote] = useState('')
  const [existing, setExisting] = useState<Checkin | null>(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { directions } = useDirections()

  useEffect(() => {
    let cancelled = false
    void studyApi
      .todayCheckin()
      .then((checkin) => {
        if (cancelled) return
        if (checkin) {
          setExisting(checkin)
          setHours(String(checkin.hours))
          setMood(checkin.mood ?? '')
          setEnergy(checkin.energy)
          setNote(checkin.note ?? '')
        }
        setLoading(false)
      })
      .catch(() => {
        if (!cancelled) {
          setError('今日打卡加载失败，请刷新重试')
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 今日打卡回填方向（Selector 需要 Direction 对象，从字典列表按 id 找回）。
  useEffect(() => {
    if (!existing || directions.length === 0) return
    setDirection((current) => current ?? directions.find((d) => d.id === existing.directionId) ?? null)
  }, [existing, directions])

  async function handleSubmit() {
    if (!direction || submitting) return
    const hoursValue = Number.parseFloat(hours)
    if (!Number.isFinite(hoursValue) || hoursValue < 0 || hoursValue > 24) {
      setError('学习时长需为 0–24 小时')
      return
    }
    setSubmitting(true)
    setError(null)
    setMessage(null)
    try {
      const saved = await studyApi.upsertCheckin({
        directionId: direction.id,
        hours: hoursValue,
        mood: mood.trim() ? mood.trim() : null,
        energy,
        note: note.trim() ? note.trim() : null,
        snapshotUrl: null,
      })
      setExisting(saved)
      setMessage(existing ? '今日打卡已更新' : '今日打卡已记录')
    } catch (e) {
      setError(e instanceof Error ? e.message : '打卡失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>今日打卡</CardTitle>
        <CardDescription>
          {existing
            ? '今天已打过卡，再次提交即更新（同一天只有一条记录）。'
            : '一天一条，重复提交即更新。学习时长会联动生成一条自报会话。'}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-1.5">
          <Label>学习方向</Label>
          <DirectionSelector value={direction} onChange={setDirection} />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="checkin-hours">学习时长（小时，0.5 步进）</Label>
            <Input
              id="checkin-hours"
              type="number"
              min={0}
              max={24}
              step={0.5}
              value={hours}
              onChange={(e) => setHours(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label>能量自评（1–5）</Label>
            <div className="flex gap-1.5">
              {[1, 2, 3, 4, 5].map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-label={`能量 ${value}`}
                  aria-pressed={energy === value}
                  onClick={() => setEnergy((current) => (current === value ? null : value))}
                  className={cn(
                    'h-8 w-8 rounded-lg border text-sm transition-colors',
                    energy === value
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-input text-muted-foreground hover:bg-accent'
                  )}
                >
                  {value}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="checkin-mood">心情（≤32 字）</Label>
          <Input
            id="checkin-mood"
            maxLength={32}
            value={mood}
            onChange={(e) => setMood(e.target.value)}
            placeholder="例如：平静、充实"
          />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="checkin-note">备注（≤500 字）</Label>
          <textarea
            id="checkin-note"
            rows={3}
            maxLength={500}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="今天做了什么、卡在哪里"
            className="min-h-[72px] w-full rounded-lg border border-input bg-transparent px-2.5 py-2 text-sm transition-colors outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
          />
        </div>

        {error && <p className="text-xs text-destructive">{error}</p>}
        {message && <p className="text-xs text-emerald-600 dark:text-emerald-400">{message}</p>}

        <Button disabled={!direction || loading || submitting} onClick={() => void handleSubmit()}>
          {submitting ? '提交中…' : existing ? '更新今日打卡' : '提交打卡'}
        </Button>
      </CardContent>
    </Card>
  )
}
