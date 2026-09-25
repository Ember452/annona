import axios, { AxiosInstance, AxiosRequestConfig } from 'axios'
import type { Result } from '../types/api'

/**
 * annona 前端所有 HTTP 请求的<b>唯一</b>入口。
 *
 * <p>约定：后端一律返回 HTTP 200 + {@link Result}；业务成功 = `code === 0`（对齐
 * `annona-common/Result.SUCCESS_CODE`），其他 code 视为业务失败并由本拦截器转成 rejected
 * Promise。这样调用侧只需要 `.then(data)` / `.catch(err)`，不必各自拆 `Result`。
 *
 * <p>页面与组件禁止 import 原生 `axios`；只用本文件的 `request.get/post/put/patch/delete`。
 *
 * <p>Type schema in `src/types/api.gen.ts` (regenerated via `pnpm gen:api`).
 */

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? ''

/** 与 `io.annona.common.result.Result.SUCCESS_CODE` 对齐。 */
export const SUCCESS_CODE = 0

const instance: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60_000,
})

function isResult(value: unknown): value is Result {
  return (
    value !== null &&
    typeof value === 'object' &&
    typeof (value as Result).code === 'number' &&
    typeof (value as Result).message === 'string'
  )
}

instance.interceptors.response.use(
  (response) => {
    if (isResult(response.data)) {
      if (response.data.code === SUCCESS_CODE) {
        // 成功：把 Result 拆掉，只把 data 交给业务层
        response.data = response.data.data
      } else {
        // 业务失败：抛带 message 的 Error，供 UI toast 直接显示
        return Promise.reject(new Error(response.data.message || '请求失败'))
      }
    }
    return response
  },
  (error) => {
    // 到这里是 HTTP 层失败（后端未响应或非 2xx），后端约定的 Result 路径不会走到这里
    const status: number | undefined = error.response?.status
    const message = status
      ? `请求失败（HTTP ${status}），请稍后重试`
      : '网络连接失败，请检查后端服务是否启动'
    return Promise.reject(new Error(message))
  },
)

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
