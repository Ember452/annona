import { NavLink, Outlet } from 'react-router-dom'
import {
  BookOpenIcon,
  CalendarDaysIcon,
  HouseIcon,
  MessagesSquareIcon,
  SparklesIcon,
  SproutIcon,
  type LucideIcon,
} from 'lucide-react'

import { cn } from '@/lib/utils'
import { PLATEAUS, ROUTES, type RouteKey } from '@/constants/routes'

/** 入口图标映射：key 与 PLATEAUS 对应，图标语义随文案走。 */
const PLATEAU_ICONS: Record<RouteKey, LucideIcon> = {
  HOME: HouseIcon,
  STUDY: BookOpenIcon,
  INTERVIEW: MessagesSquareIcon,
  QA: SparklesIcon,
  PLAN: CalendarDaysIcon,
}

/**
 * 全局壳：玻璃侧边栏（品牌 + 五平级入口）+ 主区域 <Outlet/>。
 *
 * <p>配色全部来自全局设计令牌（globals.css 的 sidebar/surface 层），与具体场景解耦：
 * P2-04 增加新场景时本文件零改动。入口元数据统一取自 constants/routes，
 * 这里只负责「怎么展示」，不关心「有哪些入口」。
 */
export default function AppLayout() {
  return (
    <div className="flex min-h-dvh">
      <aside className="sticky top-0 flex h-dvh w-64 shrink-0 flex-col border-r border-sidebar-border bg-sidebar/95 p-4 backdrop-blur-xl">
        <NavLink
          to={ROUTES.HOME}
          className="flex shrink-0 items-center gap-2.5 rounded-lg px-2 py-1"
        >
          <span className="flex size-8 items-center justify-center rounded-full bg-primary text-primary-foreground">
            <SproutIcon className="size-4" />
          </span>
          <span className="text-sm font-semibold tracking-tight">annona · 年轮</span>
        </NavLink>

        <nav className="mt-6 flex-1" aria-label="主导航">
          <ul className="space-y-1">
            {PLATEAUS.map(({ key, path, label, hint }) => {
              const Icon = PLATEAU_ICONS[key]
              return (
                <li key={key}>
                  <NavLink
                    to={path}
                    end={path === ROUTES.HOME}
                    className={({ isActive }) =>
                      cn(
                        'group flex items-start gap-3 rounded-lg px-3 py-2 text-sm transition-colors outline-none',
                        isActive
                          ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                          : 'text-sidebar-foreground/60 hover:bg-sidebar-accent/50 hover:text-sidebar-foreground focus-visible:bg-sidebar-accent/50 focus-visible:text-sidebar-foreground'
                      )
                    }
                  >
                    {({ isActive }) => (
                      <>
                        <Icon
                          className={cn(
                            'mt-0.5 size-4 shrink-0 transition-colors',
                            isActive
                              ? 'text-primary'
                              : 'text-sidebar-foreground/40 group-hover:text-sidebar-foreground/70'
                          )}
                        />
                        <span className="min-w-0">
                          <span className="block font-medium leading-5">{label}</span>
                          <span
                            className={cn(
                              'mt-0.5 block text-xs leading-4',
                              isActive
                                ? 'text-sidebar-accent-foreground/60'
                                : 'text-sidebar-foreground/35'
                            )}
                          >
                            {hint}
                          </span>
                        </span>
                      </>
                    )}
                  </NavLink>
                </li>
              )
            })}
          </ul>
        </nav>

        <footer className="shrink-0 px-3 text-[10px] text-sidebar-foreground/30">
          基座就绪 · 内容按 P1 计划渐进落地
        </footer>
      </aside>

      <main className="min-w-0 flex-1 p-8">
        <Outlet />
      </main>
    </div>
  )
}
