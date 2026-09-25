import { lazy } from 'react'
import { createBrowserRouter } from 'react-router-dom'
import AppLayout from '../layouts/AppLayout'
import { CHILD_PATHS, ROUTES } from '../constants/routes'

/**
 * 路由集中表。<b>入口平级</b>是本项目三条设计主张之一（见
 * docs/annona-项目设计文档.md 顶部），代码里落为：`/study` `/interview` `/qa` `/plan`
 * 四条顶层路由彼此无先后主次；`/` 只做概览与快捷入口。
 *
 * <p>每个 page 用 lazy() 拆包，避免首屏把 5 个入口的组件全打进来；P2 会按 D 表
 * 加更细粒度分包（如 three.js 独立 chunk）。
 */
const HomePage = lazy(() => import('../pages/home'))
const StudyPage = lazy(() => import('../pages/study'))
const InterviewPage = lazy(() => import('../pages/interview'))
const QaPage = lazy(() => import('../pages/qa'))
const PlanPage = lazy(() => import('../pages/plan'))

export const router = createBrowserRouter([
  {
    path: ROUTES.HOME,
    element: <AppLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: CHILD_PATHS.STUDY, element: <StudyPage /> },
      { path: CHILD_PATHS.INTERVIEW, element: <InterviewPage /> },
      { path: CHILD_PATHS.QA, element: <QaPage /> },
      { path: CHILD_PATHS.PLAN, element: <PlanPage /> },
    ],
  },
])
