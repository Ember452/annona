import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

import { authApi, type AuthUser } from '@/api/auth'
import { ApiError, SESSION_LOST_EVENT } from '@/api/request'

/**
 * 登录态（登录 UI 落地批，见 identity-provider-modes ADR 修订记录 2026-09-27）。
 *
 * <p>挂载即探测 `/api/me`——三种身份模式下都成立：local 无会话 → 1004 → user=null 守卫
 * 跳登录；none 恒成功 → 直达首页；platform 由反代注入凭据。会话失效（2004 过期/被踢）
 * 不在请求处逐个处理：拦截器统一派发 {@code SESSION_LOST_EVENT}，这里清态，跳转交给
 * 守卫（AppLayout）完成——api 层不感知路由。
 *
 * <p><b>不可达 ≠ 未登录</b>（2026-09-27 补）：探测失败时按错误类型分流——业务码
 * 1004/2004 是后端明确说"未登录"，走登录页；其余（网络断、代理 500、非会话类业务码）
 * 一律判为 {@link unreachable}=true，守卫渲染"无法连接后端"面板而非登录表单——
 * 把"进不去"诚实归因，而不是让用户对一个连不上的后端反复输密码。retry 重探。
 */
interface AuthContextValue {
  user: AuthUser | null
  /** 挂载探测进行中；守卫在此期间显示加载，不做未登录判定。 */
  loading: boolean
  /** 探测失败且失败原因不是"未登录"（网络断/后端挂）；守卫据此渲染不可达面板。 */
  unreachable: boolean
  retry(): Promise<void>
  login(email: string, password: string): Promise<void>
  register(email: string, password: string): Promise<void>
  logout(): Promise<void>
}

/** 后端明确表达"未认证"的业务码；其余探测失败都按不可达处理。 */
const UNAUTHENTICATED_CODES = new Set([1004, 2004])

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [unreachable, setUnreachable] = useState(false)

  const probe = useCallback(async () => {
    try {
      setUser(await authApi.me())
      setUnreachable(false)
    } catch (e) {
      setUser(null)
      const unauthenticated = e instanceof ApiError && e.code !== undefined
        && UNAUTHENTICATED_CODES.has(e.code)
      setUnreachable(!unauthenticated)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void probe()
  }, [probe])

  useEffect(() => {
    const onSessionLost = () => setUser(null)
    window.addEventListener(SESSION_LOST_EVENT, onSessionLost)
    return () => window.removeEventListener(SESSION_LOST_EVENT, onSessionLost)
  }, [])

  const retry = useCallback(async () => {
    setLoading(true)
    await probe()
  }, [probe])

  const login = useCallback(async (email: string, password: string) => {
    await authApi.login(email, password)
    setUser(await authApi.me())
    setUnreachable(false)
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
        setUnreachable(false)
      } catch {
        setUser(null)
      }
    }
  }, [])

  const value = useMemo(
    () => ({ user, loading, unreachable, retry, login, register, logout }),
    [user, loading, unreachable, retry, login, register, logout],
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
