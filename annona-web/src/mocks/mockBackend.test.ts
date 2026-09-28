import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'

import { createMockAdapter, mockRespond } from './mockBackend'

function cfg(method: string, url: string, data?: unknown): InternalAxiosRequestConfig {
  // adapter 收到的 config 已过 transformRequest（data 为序列化串、headers 就位）；
  // 测试里只需满足类型与取值路径
  return { method, url, data: data === undefined ? undefined : JSON.stringify(data), headers: {} } as InternalAxiosRequestConfig
}

describe('mockBackend（VITE_MOCK_BACKEND 纯前端模式路由表）', () => {
  // MOCK_ENABLED 是模块级常量（读构建期内联的 import.meta.env），所以本用例不能
  // 靠“测试环境恰好没设这个变量”来断言：annona-web/.env.local 里开 VITE_MOCK_BACKEND=1
  // 做纯前端开发时，同一个断言会在本机红、在 CI 绿（环境耦合的测试等于没有测试）。
  // 因此两种状态都显式 stub，并 resetModules 重读常量。
  describe('开关语义（不依赖本机 .env.local）', () => {
    beforeEach(() => {
      vi.resetModules()
      vi.unstubAllEnvs()
    })

    it('未设 VITE_MOCK_BACKEND 时不启用（CI / 生产构建不含该开关）', async () => {
      vi.stubEnv('VITE_MOCK_BACKEND', '')
      const { MOCK_ENABLED: off } = await import('./mockBackend')
      expect(off).toBe(false)
    })

    it('只有显式置 1 才启用纯前端模式', async () => {
      vi.stubEnv('VITE_MOCK_BACKEND', '1')
      const { MOCK_ENABLED: on } = await import('./mockBackend')
      expect(on).toBe(true)
    })

    it('生产构建（DEV=false）即使带开关也不启用——防本机 .env.local 静默污染产物', async () => {
      // 复现 2026-09-28 的真实事故：`.env.local` 里的 VITE_MOCK_BACKEND=1 没关就
      // `vite build`，mock 被编进 jar 里的 SPA，所有 API 请求本地应答，
      // 上传看起来像后端 404。那时仅靠“记得改环境变量”拦不住。
      const dev = import.meta.env.DEV
      try {
        vi.stubEnv('VITE_MOCK_BACKEND', '1')
        import.meta.env.DEV = false
        const { MOCK_ENABLED: prodBundle } = await import('./mockBackend')
        expect(prodBundle).toBe(false)
      } finally {
        import.meta.env.DEV = dev
      }
    })
  })

  it('身份端点：me 返回 mock 用户，login/logout/register 恒成功', () => {
    const me = mockRespond('get', '/api/me', undefined)
    expect(me).toMatchObject({ code: 0, data: { id: expect.any(String), roles: ['USER'] } })
    expect(mockRespond('post', '/api/auth/login', { email: 'a@b.c', password: 'x' }).code).toBe(0)
    expect(mockRespond('post', '/api/auth/logout', undefined).code).toBe(0)
    const register = mockRespond('post', '/api/auth/register', { email: 'x@y.z' })
    expect(register).toMatchObject({ code: 0, data: { email: 'x@y.z', role: 'USER' } })
  })

  it('业务端点：方向列表、今日会话与打卡（无打卡为 null）', () => {
    const directions = mockRespond('get', '/api/directions', undefined)
    expect(directions).toMatchObject({ code: 0 })
    expect((directions as { data: unknown[] }).data.length).toBeGreaterThanOrEqual(3)

    const sessions = mockRespond('get', '/api/study/sessions/today', undefined)
    expect((sessions as { data: unknown[] }).data.length).toBeGreaterThanOrEqual(1)

    expect(mockRespond('get', '/api/study/checkins/today', undefined)).toMatchObject({
      code: 0,
      data: null,
    })
  })

  it('未实现的端点返回业务失败 1002，而不是挂死或 404', () => {
    const missing = mockRespond('post', '/api/definitely-not-a-route', undefined)
    expect(missing).toMatchObject({ code: 1002 })
    expect((missing as { message: string }).message).toContain('/api/definitely-not-a-route')
  })

  it('adapter 包装：Result 体走 HTTP 200，供拦截器按既有约定拆包', async () => {
    const adapter = createMockAdapter()
    const response = await adapter(cfg('get', '/api/me'))
    expect(response.status).toBe(200)
    expect(response.data).toMatchObject({ code: 0 })
  })
})
