import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authApi, type AuthUser } from '@/api/auth'
import { SESSION_LOST_EVENT } from '@/api/request'
import { AuthProvider, useAuth } from './auth'

vi.mock('@/api/auth', () => ({
  authApi: {
    me: vi.fn(),
    login: vi.fn().mockResolvedValue(undefined),
    register: vi.fn().mockResolvedValue({ id: 'u1', email: 'a@b.c', role: 'USER' }),
    logout: vi.fn().mockResolvedValue(undefined),
  },
}))

const USER: AuthUser = { id: 'u1', displayName: null, roles: ['USER'] }

function renderAuth() {
  return renderHook(() => useAuth(), {
    wrapper: ({ children }) => <AuthProvider>{children}</AuthProvider>,
  })
}

describe('useAuth：会话探测、失效清态与登出', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })
  afterEach(() => {
    // RTL 的 auto-cleanup 依赖全局 afterEach；本文件显式 import vitest API，需手动清
    cleanup()
  })

  it('挂载探测成功 → 登录态就绪、loading 结束', async () => {
    vi.mocked(authApi.me).mockResolvedValue(USER)
    const { result } = renderAuth()
    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.user).toEqual(USER)
  })

  it('挂载探测被拒（未登录/过期）→ user 为 null，守卫据此跳登录页', async () => {
    vi.mocked(authApi.me).mockRejectedValue(new Error('1004'))
    const { result } = renderAuth()
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.user).toBeNull()
  })

  it('SESSION_LOST_EVENT → 清空登录态（会话过期/被踢的统一出口）', async () => {
    vi.mocked(authApi.me).mockResolvedValue(USER)
    const { result } = renderAuth()
    await waitFor(() => expect(result.current.user).not.toBeNull())

    act(() => {
      window.dispatchEvent(new CustomEvent(SESSION_LOST_EVENT))
    })
    expect(result.current.user).toBeNull()
  })

  it('login：先调登录端点再探测 /api/me，成功即持登录态', async () => {
    vi.mocked(authApi.me).mockRejectedValueOnce(new Error('1004')).mockResolvedValueOnce(USER)
    const { result } = renderAuth()
    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(async () => {
      await result.current.login('a@b.c', 'password-8')
    })
    expect(authApi.login).toHaveBeenCalledWith('a@b.c', 'password-8')
    expect(result.current.user).toEqual(USER)
  })

  it('logout（local 模式）：登出后探测失败 → 清态，守卫跳登录页', async () => {
    vi.mocked(authApi.me).mockResolvedValueOnce(USER).mockRejectedValueOnce(new Error('1004'))
    const { result } = renderAuth()
    await waitFor(() => expect(result.current.user).not.toBeNull())

    await act(async () => {
      await result.current.logout()
    })
    expect(authApi.logout).toHaveBeenCalled()
    expect(result.current.user).toBeNull()
  })

  it('logout（none 模式语义）：登出后探测恒成功 → 保留登录态，用户不被踢到无法登录的页面', async () => {
    // none 模式 /api/me 恒成功（bootstrap 身份）；此前直接 setUser(null) 会把用户
    // 踢到 /login，而该模式根本无法登录 → 卡死。回归用例锁死"重探测"行为。
    vi.mocked(authApi.me).mockResolvedValue(USER)
    const { result } = renderAuth()
    await waitFor(() => expect(result.current.user).not.toBeNull())

    await act(async () => {
      await result.current.logout()
    })
    expect(result.current.user).toEqual(USER)
  })
})
