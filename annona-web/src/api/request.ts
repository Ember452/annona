import axios, { AxiosInstance, AxiosRequestConfig } from 'axios'
import type { Result } from '../types/api'

/**
 * annona 前端所有 HTTP 请求的<b>唯一</b>入口。
 *
 * <p>约定（与 {@code io.annona.config.web.GlobalExceptionHandler} 对齐）：
 * <ul>
 *   <li><b>业务失败</b>：HTTP 200 + {@link Result}，失败靠 {@code code !== 0} 判定
 *       （{@code SUCCESS_CODE = 0} 对齐 {@code Result.SUCCESS_CODE}），本拦截器把它
 *       转成 rejected Promise，调用侧只需 {@code .then(data)} / {@code .catch(err)}。</li>
 *   <li><b>传输与路由层失败</b>（404 / 405 / 400 / 500）：<b>真实 HTTP 状态码</b> +
 *       同样的 {@code Result} 响应体，因此走下面的 error 分支。两类失败都保留
 *       {@code Result} 形状，所以文案仍可从 {@code message} 取。</li>
 * </ul>
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
    // 到这里是 HTTP 层失败（404/405/400/500 或后端未响应）。
    // 后端仍会返 Result 体，优先用它的 message，退回到状态码文案。
    const status: number | undefined = error.response?.status
    const fromBody = (error.response?.data as { message?: string } | undefined)?.message
    const message = fromBody
      ?? (status
        ? `请求失败（HTTP ${status}），请稍后重试`
        : '网络连接失败，请检查后端服务是否启动')
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
