import { useRouteError } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { ROUTES } from '@/constants/routes'

/**
 * 路由树错误边界（errorElement）。兜两类现在没有出口的故障：
 * ① 渲染期异常（此前整站白屏 + React Router 默认错误页露堆栈）；
 * ② lazy chunk 加载 404——重新部署后旧标签页持有的旧 hash chunk 已不存在，是线上
 * 最常见的"突然白屏"。给"刷新重试"的引导而不是只报错。
 */
export default function RouteError() {
  const error = useRouteError()
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-background p-6 text-center">
      <h1 className="font-heading text-xl font-semibold tracking-tight">页面出了点问题</h1>
      <p className="max-w-md text-sm text-muted-foreground">
        可能是页面渲染异常，或是站点刚更新过、本页资源已过期。
        {error instanceof Error && `（${error.message}）`}
      </p>
      <div className="flex gap-3">
        <Button variant="outline" onClick={() => window.location.reload()}>
          刷新重试
        </Button>
        <Button variant="ghost" onClick={() => window.location.assign(ROUTES.HOME)}>
          回首页
        </Button>
      </div>
    </div>
  )
}
