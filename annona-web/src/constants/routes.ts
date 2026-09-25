/**
 * 路由常量集中地。设计主张"入口平级"（见 docs/annona-项目设计文档.md 顶部）
 * 在代码里的落点：四个入口彼此路径平级、无主次，首页作为概览层。
 *
 * <p>禁止在页面/组件里写字面量路径；`import { ROUTES } from '@/constants/routes'`。
 */
export const ROUTES = {
  HOME: '/',
  STUDY: '/study',
  INTERVIEW: '/interview',
  QA: '/qa',
  PLAN: '/plan',
} as const

export type RouteKey = keyof typeof ROUTES

/** Router 内部嵌套 <Route path=...> 用的相对路径，去掉前导 `/`。 */
export const CHILD_PATHS = {
  STUDY: 'study',
  INTERVIEW: 'interview',
  QA: 'qa',
  PLAN: 'plan',
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
  { key: 'PLAN', path: ROUTES.PLAN, label: '计划日程', hint: '学习计划、面试日程与突击入口' },
]
