import { lazy } from 'react'
import { createBrowserRouter } from 'react-router-dom'
import AppLayout from '@/layouts/AppLayout'
import LoginPage from '@/pages/login'
import NotFoundPage from '@/pages/not-found'
import RouteError from '@/pages/error'
import { CHILD_PATHS, LOGIN_PATH, ROUTES } from '@/constants/routes'

/**
 * 路由集中表。<b>入口平级</b>是本项目三条设计主张之一（见
 * docs/annona-项目设计文档.md 顶部），代码里落为：`/study` `/interview` `/qa` `/plan`
 * 四条顶层路由彼此无先后主次；`/` 只做概览与快捷入口。
 *
 * <p>每个 page 用 lazy() 拆包，避免首屏把 5 个入口的组件全打进来；P2 会按 D 表
 * 加更细粒度分包（如 three.js 独立 chunk）。登录页、错误页与 404 页刻意<b>不</b>拆包：
 * 前者是守卫外侧的第一跳；后两者必须保证在业务 chunk 加载失败（部署后旧标签页的旧
 * hash 404）时仍可渲染——兜底自己再 lazy 就同归于尽了。
 */
const HomePage = lazy(() => import('../pages/home'))
const StudyPage = lazy(() => import('../pages/study'))
const InterviewPage = lazy(() => import('../pages/interview'))
const QaPage = lazy(() => import('../pages/qa'))
const PlanPage = lazy(() => import('../pages/plan'))

export const router = createBrowserRouter([
  {
    // pathless 顶层布局路由：errorElement 兜住整棵路由树——渲染异常与 lazy chunk
    // 加载失败此前都会直接裸奔（React Router 默认错误页会露出堆栈样式）
    errorElement: <RouteError />,
    children: [
      { path: LOGIN_PATH, element: <LoginPage /> },
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
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
