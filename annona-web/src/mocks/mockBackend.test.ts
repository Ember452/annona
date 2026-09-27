import { describe, expect, it } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'

import { createMockAdapter, MOCK_ENABLED, mockRespond } from './mockBackend'

function cfg(method: string, url: string, data?: unknown): InternalAxiosRequestConfig {
  // adapter 收到的 config 已过 transformRequest（data 为序列化串、headers 就位）；
  // 测试里只需满足类型与取值路径
  return { method, url, data: data === undefined ? undefined : JSON.stringify(data), headers: {} } as InternalAxiosRequestConfig
}

describe('mockBackend（VITE_MOCK_BACKEND 纯前端模式路由表）', () => {
  it('未设 env 时标志恒为 false（CI/生产构建不含该开关）', () => {
    // 本测试文件运行于未设 VITE_MOCK_BACKEND 的环境；若有人未来在测试环境注入该
    // env，此断言会提醒他去检查 adapter 接入路径
    expect(MOCK_ENABLED).toBe(false)
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
