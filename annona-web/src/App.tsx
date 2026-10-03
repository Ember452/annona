import { Suspense } from 'react'
import { RouterProvider } from 'react-router-dom'
import { LoaderCircleIcon } from 'lucide-react'

import { router } from '@/router'
import { AuthProvider } from '@/stores/auth'
import { DirectionsProvider } from '@/stores/directions'
import { SceneProvider } from '@/stores/scene'

/**
 * 应用根：SceneProvider（html[data-scene] 全局主题，最外层——所有页面吃同一份
 * CSS 场景变量）→ AuthProvider（/api/me 会话探测与失效监听）→ DirectionsProvider
 * （方向字典全局单份，依赖登录态取数）→ 路由树。provider 挂在路由外——守卫与各
 * 页面都消费它们，且数据获取不随路由切换而重置。
 */
export default function App() {
  return (
    <SceneProvider>
      <AuthProvider>
        <DirectionsProvider>
          <Suspense
            fallback={
              <div className="flex min-h-dvh flex-col items-center justify-center gap-3 bg-background text-muted-foreground">
                <LoaderCircleIcon className="size-5 animate-spin text-primary" />
                <span className="text-sm">加载中…</span>
              </div>
            }
          >
            <RouterProvider router={router} />
          </Suspense>
        </DirectionsProvider>
      </AuthProvider>
    </SceneProvider>
  )
}
