import { Link } from 'react-router-dom'

import { buttonVariants } from '@/components/ui/button'
import { ROUTES } from '@/constants/routes'

/** 未匹配路由的 404 页：SPA fallback 会把任意深链送进前端，这里兜住无匹配的路径。 */
export default function NotFoundPage() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-background p-6 text-center">
      <h1 className="font-heading text-2xl font-semibold tracking-tight">404</h1>
      <p className="text-sm text-muted-foreground">这个页面不存在，或还没有建起来。</p>
      <Link to={ROUTES.HOME} className={buttonVariants()}>
        回首页
      </Link>
    </div>
  )
}
