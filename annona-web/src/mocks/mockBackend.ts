import type { AxiosAdapter, AxiosRequestConfig, AxiosResponse } from 'axios'

import type { Direction } from '@/types/direction'
import type {
  Checkin,
  StatsDayMinutes,
  StatsDirectionMinutes,
  StatsOverview,
  StudySession,
} from '@/types/study'

/**
 * 纯前端模式（VITE_MOCK_BACKEND=1，2026-09-27）：不启动后端时调前端样式/交互用。
 *
 * <p>实现点选在 axios <b>adapter</b>——所有请求在本地按路由表应答为合法的
 * {@code Result} 体，拦截器拆包/错误归一/AuthContext 流程原样复用，登录即进、
 * 方向/会话/打卡页有假数据。路由表只覆盖当前存在的端点；未覆盖的端点返回
 * {@code code=1002}（资源不存在），页面显示既有错误态而不是挂死。
 *
 * <p>仅开发便利，不是测试基建：端到端行为规格仍由 CI 的真后端 e2e 负责
 * （后端集成测试契约见 specs/2026-09-25-dockerless-local-dev-adr.md）。
 *
 * <p>不得进生产包：开关同时受 {@code DEV} 约束。原注释声称“生产构建不可能带该 env”
 * ——那句是错的：`vite build` 同样读 `.env.local`，所以一个忘关的本机开关会把 mock 编译进
 * 产物（2026-09-28 实测：打好的 jar 里 SPA 拦截全部 API，上传报“mock 未实现该端点”，
 * 表面看像后端 404）。现在不依赖开发者记得改环境变量。
 */

/**
 * 是否启用纯前端模式。读 import.meta.env（构建期内联），不提供运行时开关。
 *
 * <p>必须 {@code DEV &&} ：`vite build`（包括产物直写 `annona-server` 静态目录的那次）
 * 与 {@code vite dev} 共享同一个 `.env.local`，少了 DEV 限定，本机遗留的开关会静默把
 * mock 打进生产 bundle。
 */
export const MOCK_ENABLED = import.meta.env.DEV && import.meta.env.VITE_MOCK_BACKEND === '1'

if (MOCK_ENABLED) {
  console.info('[annona] VITE_MOCK_BACKEND=1 —— 所有请求返回本地 fixtures，不发网络请求')
}

/** mock 身份。与后端 none 模式固定 UUID 刻意不同，避免误当真实用户排查。 */
export const MOCK_USER_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

const MOCK_USER = { id: MOCK_USER_ID, displayName: 'Mock User', roles: ['USER'] }

/** P2-07 资料/头像的内存态：patch 与回滚直接改这两个对象，刷新后保持一致。 */
const MOCK_PROFILE = {
  id: MOCK_USER_ID,
  email: 'mock@annona.local',
  nickname: 'Mock 同学',
  bio: '正在准备秋招',
  timezone: 'Asia/Shanghai',
  themeKey: 'rainforest',
  avatarObjectKey: 'avatars/mock/current.png' as string | null,
}
const MOCK_AVATAR_HISTORY: string[] = ['avatars/mock/previous.png']

function iso(msAgo: number): string {
  return new Date(Date.now() - msAgo).toISOString()
}

const DIRECTIONS: Direction[] = [
  {
    id: '11111111-1111-1111-1111-111111111111',
    key: 'java-concurrency',
    name: 'Java 并发',
    origin: 'SKILL_BUILTIN',
    kbDocId: null,
    status: 'ACTIVE',
    createdAt: iso(30 * 86_400_000),
  },
  {
    id: '22222222-2222-2222-2222-222222222222',
    key: 'system-design',
    name: '系统设计',
    origin: 'SKILL_BUILTIN',
    kbDocId: null,
    status: 'ACTIVE',
    createdAt: iso(20 * 86_400_000),
  },
  {
    id: '33333333-3333-3333-3333-333333333301',
    key: 'custom-paper-reading',
    name: '论文研读',
    origin: 'USER_CUSTOM',
    kbDocId: null,
    status: 'ACTIVE',
    createdAt: iso(5 * 86_400_000),
  },
]

function mockSession(id: string, directionId: string, opts: {
  endAt: string | null
  minutes: number | null
  quality: StudySession['quality']
}): StudySession {
  return {
    id,
    directionId,
    mode: 'POMODORO',
    startAt: iso(2 * 3_600_000),
    endAt: opts.endAt,
    minutes: opts.minutes,
    quality: opts.quality,
  }
}

const SESSIONS: StudySession[] = [
  mockSession('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', '11111111-1111-1111-1111-111111111111', {
    endAt: iso(3_600_000),
    minutes: 25,
    quality: 'VERIFIED',
  }),
  mockSession('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2', '22222222-2222-2222-2222-222222222222', {
    endAt: null,
    minutes: null,
    quality: null,
  }),
]

const CHECKIN: Checkin = {
  id: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
  directionId: '11111111-1111-1111-1111-111111111111',
  // fixtures 仅供展示，日期取本地日期即可（真实口径 Asia/Shanghai 归后端）
  day: new Date().toISOString().slice(0, 10),
  hours: 1.5,
  mood: '专注',
  energy: 4,
  note: null,
  snapshotUrl: null,
  createdAt: iso(6 * 3_600_000),
}

function success<T>(data: T): MockResult {
  return { code: 0, message: 'ok', data }
}

function notImplemented(method: string, url: string): MockResult {
  return { code: 1002, message: `mock 未实现该端点：${method.toUpperCase()} ${url}`, data: null }
}

/** 请求体解析：adapter 真实路径下 data 已被 axios 序列化为 JSON 串；
 *  直调 mockRespond（测试/调试）传原始对象也兼容。 */
function parseBody(data: AxiosRequestConfig['data']): Record<string, unknown> {
  if (data == null) return {}
  if (typeof data === 'string') {
    try {
      const parsed: unknown = JSON.parse(data)
      return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
    } catch {
      return {}
    }
  }
  return typeof data === 'object' ? (data as Record<string, unknown>) : {}
}

/** 与后端 Result 同形的 mock 应答体。 */
interface MockResult {
  code: number
  message: string
  data: unknown
}

/** 路由表：method + url → Result 体。未命中返回 1002，页面走既有错误态。 */
export function mockRespond(method: string, url: string, body: AxiosRequestConfig['data']): MockResult {
  const m = method.toLowerCase()
  const path = url.split('?')[0]

  if (path === '/api/me') return success(MOCK_USER)
  if (path === '/api/auth/login' || path === '/api/auth/logout') return success(null)
  if (path === '/api/auth/register') {
    const email = typeof parseBody(body).email === 'string' ? parseBody(body).email : 'mock@annona.local'
    return success({ id: MOCK_USER_ID, email, role: 'USER' })
  }

  if (path === '/api/directions' && m === 'get') return success(DIRECTIONS)
  if (path === '/api/directions' && m === 'post') {
    const name = typeof parseBody(body).name === 'string' ? (parseBody(body).name as string) : '新方向'
    return success({
      id: '33333333-3333-3333-3333-333333333302',
      key: `custom-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-') || 'unnamed'}`,
      name,
      origin: 'USER_CUSTOM',
      kbDocId: null,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    })
  }
  if (/^\/api\/directions\/[^/]+\/archive$/.test(path)) return success(null)
  if (/^\/api\/directions\/[^/]+\/kb-doc$/.test(path)) {
    const id = path.split('/')[3]
    const target = DIRECTIONS.find((d) => d.id === id) ?? DIRECTIONS[2]
    const kbDocId = parseBody(body).kbDocId
    return success({ ...target, origin: 'KNOWLEDGE_BASE', kbDocId: typeof kbDocId === 'string' ? kbDocId : null })
  }

  // knowledge（P1a-05）：mock 模式按约定只保证「页面可开、UI 可看、无数据」——
  // 上传/分块预览/删除需要真后端（解析、S3、embedding 无法在前端模拟），仍走 1002 错误态
  if (path === '/api/knowledge/docs' && m === 'get') return success([])

  // qa（P1a-08）：会话与历史给空列表供样式开发；流式提问不在此拦截——SSE 走 fetch
  // 不经过 axios adapter，mock 短路在 api/qa.ts 的 askStream 内（MOCK_ENABLED 门）
  if (path === '/api/qa/sessions' && m === 'get') return success([])
  if (/^\/api\/qa\/sessions\/[^/]+\/messages$/.test(path) && m === 'get') return success([])

  if (path === '/api/study/sessions/today') return success(SESSIONS)
  if (path === '/api/study/sessions' && m === 'post') {
    const b = parseBody(body)
    return success({
      ...mockSession('dddddddd-dddd-dddd-dddd-dddddddddddd', String(b.directionId ?? ''), {
        endAt: null,
        minutes: null,
        quality: null,
      }),
      mode: b.mode === 'IMMERSIVE' ? 'IMMERSIVE' : 'POMODORO',
    })
  }
  if (/^\/api\/study\/sessions\/[^/]+\/heartbeat$/.test(path)) return success(null)
  if (/^\/api\/study\/sessions\/[^/]+\/events$/.test(path)) return success(null)
  if (/^\/api\/study\/sessions\/[^/]+\/finish$/.test(path)) {
    return success(mockSession('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', '11111111-1111-1111-1111-111111111111', {
      endAt: new Date().toISOString(),
      minutes: 25,
      quality: 'VERIFIED',
    }))
  }
  if (path === '/api/study/sessions/manual') {
    return success({
      ...mockSession('dddddddd-dddd-dddd-dddd-dddddddddddd', String(parseBody(body).directionId ?? ''), {
        endAt: new Date().toISOString(),
        minutes: 30,
        quality: 'SELF_REPORTED',
      }),
      mode: 'CHECKIN',
    })
  }

  if (path === '/api/study/checkins/today') return success(null)
  if (path === '/api/study/checkins' && m === 'post') {
    const b = parseBody(body)
    return success({
      ...CHECKIN,
      directionId: String(b.directionId ?? CHECKIN.directionId),
      hours: typeof b.hours === 'number' ? b.hours : CHECKIN.hours,
      mood: typeof b.mood === 'string' ? b.mood : null,
      energy: typeof b.energy === 'number' ? b.energy : null,
      note: typeof b.note === 'string' ? b.note : null,
    })
  }

  // study 统计（P2-01）：确定性伪随机年度数据，让热力图/柱状/分布都有形可调
  if (path === '/api/study/stats/overview') return success(mockStatsOverview())

  // study 共学（P2-05）：固定在线数供徽章样式开发
  if (path === '/api/study/presence') return success({ online: 3 })

  // 个人资料与头像（P2-07）：内存态可改，供资料编辑/头像历史 UI 样式开发；
  // 头像字节端点是 img src（不走 axios），mock 模式下自然 404 → 前端回退首字母占位
  if (path === '/api/me/profile' && m === 'get') return success(MOCK_PROFILE)
  if (path === '/api/me/profile' && m === 'patch') {
    const b = parseBody(body)
    if (typeof b.nickname === 'string') MOCK_PROFILE.nickname = b.nickname
    if (typeof b.bio === 'string') MOCK_PROFILE.bio = b.bio
    return success(MOCK_PROFILE)
  }
  if (path === '/api/me/avatar/history') return success(MOCK_AVATAR_HISTORY)
  if (path === '/api/me/avatar/rollback' && m === 'post') {
    if (MOCK_AVATAR_HISTORY.length === 0) {
      return { code: 2008, message: '没有可回滚的历史头像', data: null }
    }
    const prev = MOCK_AVATAR_HISTORY[0]
    MOCK_AVATAR_HISTORY[0] = MOCK_PROFILE.avatarObjectKey as string
    MOCK_PROFILE.avatarObjectKey = prev
    return success({ success: true, previousKey: prev })
  }

  return notImplemented(m, path)
}

/** 年度统计 fixture：约 55% 的日子有记录，有效/自报比例固定，量级够看出色阶层次。 */
function mockStatsOverview(): StatsOverview {
  const year = Number(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date()).slice(0, 4))
  const start = Date.UTC(year, 0, 1)
  const dayCount = Math.round((Date.UTC(year + 1, 0, 1) - start) / 86_400_000)
  const todayShanghai = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
  const days: StatsDayMinutes[] = []
  for (let i = 0; i < dayCount; i++) {
    const date = new Date(start + i * 86_400_000).toISOString().slice(0, 10)
    if (date > todayShanghai) break
    const seed = (i * 13 + 7) % 100
    if (seed < 45) continue
    const self = seed % 4 === 0 ? 15 + (seed % 30) : 0
    const verified = seed % 4 === 0 ? 0 : 20 + (seed % 110)
    days.push({ day: date, verifiedMinutes: verified, selfReportedMinutes: self })
  }
  const directions: StatsDirectionMinutes[] = [
    { directionId: DIRECTIONS[0].id, name: DIRECTIONS[0].name, verifiedMinutes: 1240, selfReportedMinutes: 90 },
    { directionId: DIRECTIONS[1].id, name: DIRECTIONS[1].name, verifiedMinutes: 620, selfReportedMinutes: 45 },
    { directionId: DIRECTIONS[2].id, name: DIRECTIONS[2].name, verifiedMinutes: 180, selfReportedMinutes: 0 },
  ]
  return { year, days, directions, totalCheckins: 37 }
}

/** 模拟一点网络延迟，让 loading/骨架态在纯前端模式下也可见、可调。 */
function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function createMockAdapter(): AxiosAdapter {
  return async (config: AxiosRequestConfig): Promise<AxiosResponse> => {
    const method = (config.method ?? 'get').toLowerCase()
    const body = mockRespond(method, config.url ?? '', config.data)
    await delay(150)
    // 统一 HTTP 200：业务失败（1002 未实现端点）也走 Result 体，由拦截器按 code 分流，
    // 与真实后端的业务失败出口完全同形
    return {
      data: body,
      status: 200,
      statusText: 'Mock',
      headers: {},
      config,
    } as AxiosResponse
  }
}
