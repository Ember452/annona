/**
 * 与后端 DTO 对齐的手写占位类型。
 *
 * <p>真实生成类型走 `pnpm gen:api` 写入 `./api.gen.ts`（该文件被 `.gitignore` 排除，
 * 只在开发者本机与 CI 里存在）。这里的类型只在 OpenAPI 生成前提供基础形状，
 * 避免业务代码 import 空文件。
 */

/**
 * 后端统一响应结构，与 `annona-common` 的 `io.annona.common.result.Result<T>` 一一对齐：
 * `code = 0` 表示成功，其他值参见 `io.annona.common.exception.ErrorCode`。
 */
export interface Result<T = unknown> {
  code: number
  message: string
  data: T
  /** SLF4J MDC 的 `traceId`；后端 observability 过滤器在 P0-12 落地前可能为空。 */
  traceId?: string
}

/** 分页响应形状，与后端未来的 `XxxPage` 契约对齐。 */
export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}
