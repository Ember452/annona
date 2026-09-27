import axios, { AxiosError, AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios'
import type { Result } from '@/types/api'
import { createMockAdapter, MOCK_ENABLED } from '@/mocks/mockBackend'

/**
 * annona 前端所有 HTTP 请求的<b>唯一</b>入口。
 *
 * <p>约定（与 {@code io.annona.config.web.GlobalExceptionHandler} 对齐）：
 * <ul>
 *   <li><b>业务失败</b>：HTTP 200 + {@link Result}，失败靠 {@code code !== 0} 判定
 *       （{@code SUCCESS_CODE = 0} 对齐 {@code Result.SUCCESS_CODE}），本拦截器把它
 *       转成 rejected Promise（{@link ApiError}），调用侧只需 {@code .then(data)} /
 *       {@code .catch(err)}。</li>
 *   <li><b>传输与路由层失败</b>（404 / 405 / 400 / 500）：<b>真实 HTTP 状态码</b> +
 *       同样的 {@code Result} 响应体，因此走 error 分支；同样归一成 {@link ApiError}，
 *       文案优先取响应体 message。</li>
 * </ul>
 *
 * <p>错误对象保留 {@code code} 与 {@code traceId}：code 供调用侧分支（如 2004 会话过期），
 * traceId 供用户报障时对后端日志——两者在 {@code new Error(message)} 里都会丢。
 * 会话失效（UNAUTHORIZED / SESSION_EXPIRED）统一派发 {@link SESSION_LOST_EVENT}，
 * 由 AuthContext 清态并回登录页；本文件不做路由跳转（api 层不感知路由）。
 *
 * <p>页面与组件禁止 import 原生 `axios`；只用本文件的 `request.get/post/put/patch/delete`。
 * {@code onFulfilled} / {@code onRejected} 导出仅供单测直测拦截器分支。
 *
 * <p>Type schema in `src/types/api.gen.ts` (regenerated via `pnpm gen:api`).
 */

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? ''

/** 与 `io.annona.common.result.Result.SUCCESS_CODE` 对齐。 */
export const SUCCESS_CODE = 0

/** 会话失效事件名：拦截器在 code=1004（未授权）/ 2004（已过期）时派发，AuthContext 监听。 */
export const SESSION_LOST_EVENT = 'annona:session-lost'

/** 会话失效的业务码集合（`ErrorCode.UNAUTHORIZED` / `SESSION_EXPIRED`）。 */
const SESSION_LOST_CODES = new Set([1004, 2004])

/** 业务失败/传输失败统一抛出的错误：保留后端 code 与 traceId。 */
export class ApiError extends Error {
  /** 后端 `ErrorCode` 数值；后端未响应（网络断）时为 undefined。 */
  readonly code?: number
  /** 后端 `Result.traceId`，报障时贴出即可关联服务端日志。 */
  readonly traceId?: string

  constructor(message: string, code?: number, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

function isResult(value: unknown): value is Result {
  return (
    value !== null &&
    typeof value === 'object' &&
    typeof (value as Result).code === 'number' &&
    typeof (value as Result).message === 'string'
  )
}

function notifySessionLost(code: number | undefined) {
  if (code !== undefined && SESSION_LOST_CODES.has(code) && typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(SESSION_LOST_EVENT))
  }
}

/** 成功降路：Result 拆包；业务失败转 ApiError 并在会话失效时派发事件。 */
export function onFulfilled(response: AxiosResponse): AxiosResponse | Promise<AxiosResponse> {
  if (isResult(response.data)) {
    if (response.data.code === SUCCESS_CODE) {
      // 成功：把 Result 拆掉，只把 data 交给业务层
      response.data = response.data.data
    } else {
      notifySessionLost(response.data.code)
      return Promise.reject(
        new ApiError(response.data.message || '请求失败', response.data.code, response.data.traceId),
      )
    }
  }
  return response
}

/** 失败降路：真实 4xx/5xx 的 Result 体 / 网络错误统一归一成 ApiError。 */
export function onRejected(error: unknown): Promise<never> {
  const axiosError = error as AxiosError
  const body: unknown = axiosError?.response?.data
  if (isResult(body)) {
    notifySessionLost(body.code)
    return Promise.reject(new ApiError(body.message || '请求失败', body.code, body.traceId))
  }
  const status: number | undefined = axiosError?.response?.status
  const message = status
    ? `请求失败（HTTP ${status}），请稍后重试`
    : '网络连接失败，请检查后端服务是否启动'
  return Promise.reject(new ApiError(message, undefined, undefined))
}

const instance: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60_000,
  // 纯前端模式（VITE_MOCK_BACKEND=1，仅前端样式/交互开发用）：所有请求由本地 fixtures
  // 在 adapter 层应答，拦截器与业务代码零感知；env 不设时 undefined，路径完全不变
  adapter: MOCK_ENABLED ? createMockAdapter() : undefined,
})

instance.interceptors.response.use(onFulfilled, onRejected)

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then((r) => r.data as T)
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then((r) => r.data as T)
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config).then((r) => r.data as T)
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.patch(url, data, config).then((r) => r.data as T)
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config).then((r) => r.data as T)
  },
}

export default request
