import { useCallback, useEffect, useRef, useState } from 'react'

import { studyApi } from '@/api/study'
import { useHeartbeat } from '@/hooks/useHeartbeat'

export type PomodoroMode = 'focus' | 'break'

export interface PomodoroOptions {
  /** 一次会话落定（自然到期补 finish / 主动放弃）后通知，父级据此刷新今日列表。 */
  onSessionSettled?: () => void
}

const STORAGE_KEY = 'annona-study-pomodoro'
export const BREAK_MINUTES = 5
/** 250ms tick + 绝对截止时间计算：刷新与浏览器后台节流都不会造成计时漂移（借鉴上游 focus-timer）。 */
const TICK_MS = 250

interface PomodoroSnapshot {
  mode: PomodoroMode
  round: number
  completed: number
  focusMinutes: number
  isRunning: boolean
  remaining: number
  expiresAt: number | null
  /** 进行中会话的后端 id（focus 运行/暂停时非 null；break 与空闲为 null）。 */
  sessionId: string | null
}

/** 校验式读取：任何字段形状不对都当无快照（上游 focus-timer 同款防脏数据）。 */
function load(): PomodoroSnapshot | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    if (!value || typeof value !== 'object') return null
    const snap = value as Record<string, unknown>
    const expiresAt = snap.expiresAt
    if (
      (snap.mode !== 'focus' && snap.mode !== 'break') ||
      !Number.isInteger(snap.round) || Number(snap.round) < 1 ||
      !Number.isInteger(snap.completed) || Number(snap.completed) < 0 ||
      !Number.isFinite(snap.focusMinutes) || Number(snap.focusMinutes) <= 0 ||
      typeof snap.isRunning !== 'boolean' ||
      !Number.isInteger(snap.remaining) || Number(snap.remaining) < 0 ||
      (expiresAt !== undefined && expiresAt !== null && !Number.isFinite(expiresAt)) ||
      (snap.sessionId !== null && typeof snap.sessionId !== 'string')
    ) {
      return null
    }
    return {
      mode: snap.mode as PomodoroMode,
      round: Number(snap.round),
      completed: Number(snap.completed),
      focusMinutes: Number(snap.focusMinutes),
      isRunning: snap.isRunning,
      remaining: Number(snap.remaining),
      expiresAt: typeof expiresAt === 'number' ? expiresAt : null,
      sessionId: snap.sessionId as string | null,
    }
  } catch {
    return null
  }
}

function save(snapshot: PomodoroSnapshot) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot))
  } catch {
    // 隐私模式等存不进就放弃持久化，会话降级为"刷新即失"
  }
}

function secondsUntil(expiresAt: number): number {
  return Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))
}

/**
 * 番茄钟状态机（P1a-04，借鉴 🅢 focus-timer 改造）：绝对截止时间计时、localStorage 快照
 * 恢复（恢复时已过期 → 自动补 finish，服务端按心跳判定）、sessionId 贯穿。
 *
 * <p>与上游的本质差异：上游在"完成时"上报前端算好的时长；annona 的 start 即建后端会话、
 * 运行中靠 15s 心跳留时间线、finish 由服务端判定 minutes/quality——前端不上报任何时长。
 * break 段是纯前端休息倒计时，不落会话。
 */
export function usePomodoro(options: PomodoroOptions = {}) {
  const [mode, setMode] = useState<PomodoroMode>('focus')
  const [round, setRound] = useState(1)
  const [completed, setCompleted] = useState(0)
  const [isRunning, setIsRunning] = useState(false)
  const [remaining, setRemaining] = useState(25 * 60)
  const [focusMinutes, setFocusMinutesState] = useState(25)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const [abandoning, setAbandoning] = useState(false)
  /** 自然到期/恢复补 finish 失败时挂起的会话 id：前端已切休息，但后端仍 RUNNING，给用户重试入口（评审 A3）。 */
  const [pendingFinish, setPendingFinish] = useState<string | null>(null)
  const [hydrated, setHydrated] = useState(false)

  const modeRef = useRef(mode); modeRef.current = mode
  const roundRef = useRef(round); roundRef.current = round
  const completedRef = useRef(completed); completedRef.current = completed
  const remainingRef = useRef(remaining); remainingRef.current = remaining
  const focusMinRef = useRef(focusMinutes); focusMinRef.current = focusMinutes
  const isRunningRef = useRef(isRunning); isRunningRef.current = isRunning
  const sessionIdRef = useRef(sessionId); sessionIdRef.current = sessionId
  const startingRef = useRef(false)
  const abandoningRef = useRef(false)
  const expiresAtRef = useRef<number | null>(null)
  const onSessionSettledRef = useRef(options.onSessionSettled)
  onSessionSettledRef.current = options.onSessionSettled

  function persist(running: boolean, rem: number, expiresAt: number | null) {
    save({
      mode: modeRef.current,
      round: roundRef.current,
      completed: completedRef.current,
      focusMinutes: focusMinRef.current,
      isRunning: running,
      remaining: rem,
      expiresAt,
      sessionId: sessionIdRef.current,
    })
  }

  interface NextState {
    mode: PomodoroMode
    round: number
    completed: number
    remaining: number
    isRunning: boolean
    expiresAt: number | null
    sessionId: string | null
  }

  /** refs + state 同步落地并持久化（所有状态迁移的唯一出口）。 */
  function applyState(next: NextState) {
    modeRef.current = next.mode
    roundRef.current = next.round
    completedRef.current = next.completed
    remainingRef.current = next.remaining
    isRunningRef.current = next.isRunning
    expiresAtRef.current = next.expiresAt
    sessionIdRef.current = next.sessionId
    setMode(next.mode)
    setRound(next.round)
    setCompleted(next.completed)
    setRemaining(next.remaining)
    setIsRunning(next.isRunning)
    setSessionId(next.sessionId)
    persist(next.isRunning, next.remaining, next.expiresAt)
  }

  function settleSession() {
    onSessionSettledRef.current?.()
  }

  /** 专注自然到期：后端 finish 判质量（幂等；失败时会话仍 RUNNING，列表原样展示）；休息到期切下一轮。 */
  function handleExpire() {
    const sid = sessionIdRef.current
    if (modeRef.current === 'focus') {
      if (sid) {
        // 失败不静默吞：保留 sid 到 pendingFinish，UI 给"未同步"提示 + 重试（finish 服务端幂等）
        void studyApi.finishSession(sid, false).then(settleSession).catch(() => setPendingFinish(sid))
      }
      applyState({
        mode: 'break',
        round: roundRef.current,
        completed: completedRef.current + 1,
        remaining: BREAK_MINUTES * 60,
        isRunning: true,
        expiresAt: Date.now() + BREAK_MINUTES * 60_000,
        sessionId: null,
      })
    } else {
      applyState({
        mode: 'focus',
        round: roundRef.current + 1,
        completed: completedRef.current,
        remaining: focusMinRef.current * 60,
        isRunning: false,
        expiresAt: null,
        sessionId: null,
      })
    }
  }

  // 挂载后读快照：isRunning 时按绝对截止时间恢复；已过期 → focus 补 finish / break 直接进下一轮。
  useEffect(() => {
    const snap = load()
    if (snap) {
      modeRef.current = snap.mode
      roundRef.current = snap.round
      completedRef.current = snap.completed
      focusMinRef.current = snap.focusMinutes
      sessionIdRef.current = snap.sessionId
      setMode(snap.mode)
      setRound(snap.round)
      setCompleted(snap.completed)
      setFocusMinutesState(snap.focusMinutes)
      setSessionId(snap.sessionId)
      if (snap.isRunning) {
        const deadline = snap.expiresAt ?? Date.now() + snap.remaining * 1000
        const rest = secondsUntil(deadline)
        if (rest > 0) {
          expiresAtRef.current = deadline
          remainingRef.current = rest
          setRemaining(rest)
          setIsRunning(true)
          save({ ...snap, remaining: rest, expiresAt: deadline })
        } else if (snap.mode === 'focus') {
          if (snap.sessionId) {
            void studyApi
              .finishSession(snap.sessionId, false)
              .then(settleSession)
              .catch(() => setPendingFinish(snap.sessionId))
          }
          applyState({
            mode: 'break',
            round: snap.round,
            completed: snap.completed + 1,
            remaining: BREAK_MINUTES * 60,
            isRunning: true,
            expiresAt: Date.now() + BREAK_MINUTES * 60_000,
            sessionId: null,
          })
        } else {
          applyState({
            mode: 'focus',
            round: snap.round + 1,
            completed: snap.completed,
            remaining: snap.focusMinutes * 60,
            isRunning: false,
            expiresAt: null,
            sessionId: null,
          })
        }
      } else {
        remainingRef.current = snap.remaining
        setRemaining(snap.remaining)
      }
    }
    setHydrated(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 绝对截止时间驱动：250ms tick 只做"剩余秒数重算 + 到期分流"。
  useEffect(() => {
    if (!hydrated || !isRunning) return
    const deadline = expiresAtRef.current ?? Date.now() + remainingRef.current * 1000
    expiresAtRef.current = deadline
    persist(true, remainingRef.current, deadline)

    const update = () => {
      const next = secondsUntil(deadline)
      if (next <= 0) {
        handleExpire()
        return
      }
      if (next !== remainingRef.current) {
        remainingRef.current = next
        setRemaining(next)
        persist(true, next, deadline)
      }
    }
    update()
    const interval = setInterval(update, TICK_MS)
    return () => clearInterval(interval)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hydrated, isRunning])

  /** 开始专注：先建后端会话（拿 sessionId），失败抛给调用侧展示。方向必选由 UI 保证。 */
  const start = useCallback(async (directionId: string) => {
    if (sessionIdRef.current || startingRef.current) return
    startingRef.current = true
    setStarting(true)
    try {
      const session = await studyApi.startSession({
        directionId,
        plannedMinutes: focusMinRef.current,
      })
      const seconds = focusMinRef.current * 60
      applyState({
        mode: 'focus',
        round: roundRef.current,
        completed: completedRef.current,
        remaining: seconds,
        isRunning: true,
        expiresAt: Date.now() + seconds * 1000,
        sessionId: session.id,
      })
    } finally {
      startingRef.current = false
      setStarting(false)
    }
  }, [])

  /** 暂停：会话仍 RUNNING，心跳随 active=false 停跳，暂停段会被服务端如实记为 gap。 */
  const pause = useCallback(() => {
    if (!sessionIdRef.current || !isRunningRef.current) return
    const seconds = expiresAtRef.current === null
      ? remainingRef.current
      : secondsUntil(expiresAtRef.current)
    applyState({
      mode: modeRef.current,
      round: roundRef.current,
      completed: completedRef.current,
      remaining: seconds,
      isRunning: false,
      expiresAt: null,
      sessionId: sessionIdRef.current,
    })
  }, [])

  const resume = useCallback(() => {
    if (!sessionIdRef.current || isRunningRef.current) return
    const seconds = remainingRef.current
    applyState({
      mode: modeRef.current,
      round: roundRef.current,
      completed: completedRef.current,
      remaining: seconds,
      isRunning: true,
      expiresAt: Date.now() + seconds * 1000,
      sessionId: sessionIdRef.current,
    })
  }, [])

  /** 提前结束：服务端按心跳时间线判质量（abandon 语义），本轮不记 completed。 */
  const abandon = useCallback(async () => {
    const sid = sessionIdRef.current
    if (!sid || abandoningRef.current) return
    abandoningRef.current = true
    setAbandoning(true)
    try {
      await studyApi.finishSession(sid, true)
      settleSession()
      applyState({
        mode: 'focus',
        round: roundRef.current,
        completed: completedRef.current,
        remaining: focusMinRef.current * 60,
        isRunning: false,
        expiresAt: null,
        sessionId: null,
      })
    } finally {
      abandoningRef.current = false
      setAbandoning(false)
    }
  }, [settleSession])

  const skipBreak = useCallback(() => {
    if (sessionIdRef.current || modeRef.current !== 'break') return
    applyState({
      mode: 'focus',
      round: roundRef.current + 1,
      completed: completedRef.current,
      remaining: focusMinRef.current * 60,
      isRunning: false,
      expiresAt: null,
      sessionId: null,
    })
  }, [])

  /** 清空轮次进度（仅空闲可用；运行中须先提前结束，避免后端会话悬空）。 */
  const reset = useCallback(() => {
    if (sessionIdRef.current) return
    applyState({
      mode: 'focus',
      round: 1,
      completed: 0,
      remaining: focusMinRef.current * 60,
      isRunning: false,
      expiresAt: null,
      sessionId: null,
    })
  }, [])

  /** 切换专注预设；运行/暂停中不生效（会话已带 plannedMinutes），break 段只影响下一轮。 */
  const setFocusMinutes = useCallback((minutes: number) => {
    if (sessionIdRef.current || minutes === focusMinRef.current) return
    focusMinRef.current = minutes
    setFocusMinutesState(minutes)
    if (modeRef.current === 'focus' && !isRunningRef.current) {
      remainingRef.current = minutes * 60
      setRemaining(minutes * 60)
      persist(false, minutes * 60, null)
    }
  }, [])

  /** 重试同步一个 finish 失败的到期会话（服务端幂等，成功后清 pendingFinish 并通知列表刷新）。 */
  const retryFinish = useCallback(async () => {
    const sid = pendingFinish
    if (!sid) return
    await studyApi.finishSession(sid, false)
    setPendingFinish(null)
    onSessionSettledRef.current?.()
  }, [pendingFinish])

  // 心跳内聚在这里：专注运行中每 15s 一跳，失焦暂停 + BLUR，恢复续跳。
  useHeartbeat(sessionId, isRunning && mode === 'focus')

  return {
    mode,
    round,
    completed,
    remaining,
    isRunning,
    focusMinutes,
    /** 有进行中/暂停的后端会话（决定预设禁用、放弃按钮显隐）。 */
    hasSession: sessionId !== null,
    starting,
    abandoning,
    /** 非 null 表示有一次到期会话未同步成功，UI 据此提示重试。 */
    pendingFinish,
    retryFinish,
    start,
    pause,
    resume,
    abandon,
    skipBreak,
    reset,
    setFocusMinutes,
  }
}
