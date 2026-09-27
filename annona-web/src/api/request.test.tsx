import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, onFulfilled, onRejected, SESSION_LOST_EVENT, SUCCESS_CODE } from './request'
import type { AxiosResponse } from 'axios'

function resultResponse(data: unknown): AxiosResponse {
  return { data } as AxiosResponse
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('request 拦截器（P1a-04 加固：code/traceId 保留 + 会话失效事件）', () => {
  it('code=0：拆掉 Result 只留 data', () => {
    const response = resultResponse({ code: SUCCESS_CODE, message: 'ok', data: { id: 'x' } })
    expect(onFulfilled(response)).toBe(response)
    expect(response.data).toEqual({ id: 'x' })
  })

  it('业务失败：reject 出 ApiError 且带 code 与 traceId', async () => {
    const spy = vi.spyOn(window, 'dispatchEvent')
    const response = resultResponse({ code: 2102, message: '自定义方向已达上限', traceId: 't-1' })
    await expect(onFulfilled(response)).rejects.toMatchObject({
      name: 'ApiError',
      code: 2102,
      traceId: 't-1',
      message: '自定义方向已达上限',
    })
    // 非会话失效码不派发事件
    expect(spy).not.toHaveBeenCalled()
  })

  it('会话失效（2004）：派发 SESSION_LOST_EVENT 供 AuthContext 清态', async () => {
    const spy = vi.spyOn(window, 'dispatchEvent')
    const response = resultResponse({ code: 2004, message: '登录状态已失效', traceId: 't-2' })
    await expect(onFulfilled(response)).rejects.toBeInstanceOf(ApiError)
    expect(spy).toHaveBeenCalledTimes(1)
    expect((spy.mock.calls[0]?.[0] as CustomEvent).type).toBe(SESSION_LOST_EVENT)
  })

  it('传输层失败且响应体是 Result（404/500）：同样取体里的 code/traceId', async () => {
    const spy = vi.spyOn(window, 'dispatchEvent')
    const error = { response: { status: 404, data: { code: 1002, message: '资源不存在', traceId: 't-3' } } }
    await expect(onRejected(error)).rejects.toMatchObject({ code: 1002, traceId: 't-3' })
    expect(spy).not.toHaveBeenCalled()
  })

  it('网络断（无响应）：给可执行文案且 code 为 undefined', async () => {
    await expect(onRejected(new Error('ECONNREFUSED'))).rejects.toMatchObject({
      code: undefined,
      message: '网络连接失败，请检查后端服务是否启动',
    })
  })
})
