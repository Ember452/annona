import { NavLink, Outlet } from 'react-router-dom'
import { PLATEAUS, ROUTES } from '../constants/routes'

/**
 * 全局壳：左侧栏（四平级入口 + 首页）+ 主区域 <Outlet/>。
 *
 * <p>P0-09 阶段样式极简（Tailwind 原生 utility），P2 会引入 shadcn 与主题 token 包
 * 替换这里的类名（见 docs/annona-开发计划.md §D 表）。
 */
export default function AppLayout() {
  return (
    <div className="min-h-screen flex bg-white text-neutral-900">
      <aside className="w-60 shrink-0 border-r border-neutral-200 p-4 flex flex-col">
        <div className="text-lg font-bold mb-6 tracking-tight">annona · 年轮</div>
        <nav className="flex-1">
          <ul className="space-y-1">
            {PLATEAUS.map(({ key, path, label, hint }) => (
              <li key={key}>
                <NavLink
                  to={path}
                  end={path === ROUTES.HOME}
                  className={({ isActive }) =>
                    `block px-3 py-2 rounded-md text-sm transition-colors ${
                      isActive
                        ? 'bg-neutral-900 text-white'
                        : 'hover:bg-neutral-100 text-neutral-700'
                    }`
                  }
                >
                  <span className="block font-medium">{label}</span>
                  <span className="block text-xs opacity-70 mt-0.5">{hint}</span>
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <footer className="text-[10px] text-neutral-400 mt-4">
          P0-09 骨架 · 内容待 P1 落地
        </footer>
      </aside>
      <main className="flex-1 p-8 min-w-0">
        <Outlet />
      </main>
    </div>
  )
}
