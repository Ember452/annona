import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

import { authApi, type AuthUser } from '@/api/auth'
import { SESSION_LOST_EVENT } from '@/api/request'

/**
 * 登录态（登录 UI 落地批，见 identity-provider-modes ADR 修订记录 2026-09-27）。
 *
 * <p>挂载即探测 `/api/me`——三种身份模式下都成立：local 无会话 → 1004 → user=null 守卫
 * 跳登录；none 恒成功 → 直达首页；platform 由反代注入凭据。会话失效（2004 过期/被踢）
 * 不在请求处逐个处理：拦截器统一派发 {@code SESSION_LOST_EVENT}，这里清态，跳转交给
 * 守卫（AppLayout）完成——api 层不感知路由。
 */
interface AuthContextValue {
  user: AuthUser | null
  /** 挂载探测进行中；守卫在此期间显示加载，不做未登录判定。 */
  loading: boolean
  login(email: string, password: string): Promise<void>
  register(email: string, password: string): Promise<void>
  logout(): Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    authApi
      .me()
      .then((me) => {
        if (!cancelled) setUser(me)
      })
      .catch(() => {
        if (!cancelled) setUser(null)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const onSessionLost = () => setUser(null)
    window.addEventListener(SESSION_LOST_EVENT, onSessionLost)
    return () => window.removeEventListener(SESSION_LOST_EVENT, onSessionLost)
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    await authApi.login(email, password)
    setUser(await authApi.me())
  }, [])

  const register = useCallback(
    async (email: string, password: string) => {
      await authApi.register(email, password)
      // 注册成功即登录：注册端点本身不发会话 Cookie，多一步用户手动的登录页跳转
      // 只剩摩擦；登录失败仍会落到登录页错误文案（邮箱已注册等由 ApiError 文案表达）
      await login(email, password)
    },
    [login],
  )

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      // 重新探测而不是直接置空：none（单机免登录）模式下 /api/me 恒成功——一律置空会让
      // 守卫把用户踢到 /login，而该模式没有可用的登录入口，直接卡死；local 模式探测失败
      // （1004）才真正清态走登录页。前端依旧不感知 mode，探测成败即守卫依据
      try {
        setUser(await authApi.me())
      } catch {
        setUser(null)
      }
    }
  }, [])

  const value = useMemo(
    () => ({ user, loading, login, register, logout }),
    [user, loading, login, register, logout],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth 必须在 <AuthProvider> 内使用')
  }
  return ctx
}
