/**
 * 路由常量集中地。设计主张"入口平级"（见 docs/annona-项目设计文档.md 顶部）
 * 在代码里的落点：各入口彼此路径平级、无主次，首页作为概览层。知识库入口为
 * 2026-09-27 用户拍板新增（knowledge-ingestion-adr §决策 10）。
 *
 * <p>禁止在页面/组件里写字面量路径；`import { ROUTES } from '@/constants/routes'`。
 */
export const ROUTES = {
  HOME: '/',
  STUDY: '/study',
  INTERVIEW: '/interview',
  QA: '/qa',
  KNOWLEDGE: '/knowledge',
  PLAN: '/plan',
} as const

/** 登录页路径：不进 ROUTES/PLATEAUS——它不是平级入口，而是守卫的外侧（AppLayout 的
 * PLATEAU_ICONS 对 RouteKey 穷举，无关 key 会破坏映射完整性）。 */
export const LOGIN_PATH = '/login'

/** 个人主页与设置（P2-07）：同 LOGIN_PATH，二级页不是平级入口，经侧栏底部链接进入。 */
export const PROFILE_PATH = '/profile'

export type RouteKey = keyof typeof ROUTES

/** Router 内部嵌套 <Route path=...> 用的相对路径，去掉前导 `/`。 */
export const CHILD_PATHS = {
  STUDY: 'study',
  INTERVIEW: 'interview',
  QA: 'qa',
  KNOWLEDGE: 'knowledge',
  PLAN: 'plan',
  /** 计划工作室（P2-06）：嵌在 /plan 下的详情路由。 */
  PLAN_DETAIL: 'plan/:planId',
} as const

/** 侧边栏展示用的元数据（label + 一行 hint）。 */
export interface PlateauMeta {
  key: RouteKey
  path: string
  label: string
  hint: string
}

export const PLATEAUS: PlateauMeta[] = [
  { key: 'HOME', path: ROUTES.HOME, label: '首页', hint: '学习行为概览与决策面板' },
  { key: 'STUDY', path: ROUTES.STUDY, label: '自习室', hint: '采集学习行为、专注与打卡' },
  { key: 'INTERVIEW', path: ROUTES.INTERVIEW, label: '模拟面试', hint: 'AI 出题、评估与可解释面板' },
  { key: 'QA', path: ROUTES.QA, label: '知识问答', hint: '基于知识库的 RAG 流式问答' },
  { key: 'KNOWLEDGE', path: ROUTES.KNOWLEDGE, label: '知识库', hint: '上传讲义与教材，管理分块与向量化' },
  { key: 'PLAN', path: ROUTES.PLAN, label: '计划日程', hint: '学习计划、面试日程与突击入口' },
]
