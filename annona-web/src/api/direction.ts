import { request } from './request'
import type { BindKbDocInput, CreateDirectionInput, Direction } from '../types/direction'

/**
 * direction 字典（P1a-03）四端点封装。
 *
 * <p>页面与组件禁止直接 import axios，一律走本文件——Result 拆包、业务失败转
 * rejected Promise、错误文案兜底均由 `./request` 的拦截器统一处理。
 */
const BASE = '/api/directions'

export const directionApi = {
  /** GET /api/directions：内置 + 本人 ACTIVE，内置在前、其余按名称排序。 */
  list(): Promise<Direction[]> {
    return request.get<Direction[]>(BASE)
  },

  /** POST /api/directions：即时新建并落库（origin=USER_CUSTOM）。 */
  create(input: CreateDirectionInput): Promise<Direction> {
    return request.post<Direction>(BASE, input)
  },

  /** POST /api/directions/{id}/archive：归档（唯一的"删除"路径，幂等）。 */
  archive(id: string): Promise<void> {
    return request.post<void>(`${BASE}/${id}/archive`)
  },

  /** PUT /api/directions/{id}/kb-doc：绑定知识库，origin 单向升级为 KNOWLEDGE_BASE。 */
  bindKbDoc(id: string, input: BindKbDocInput): Promise<Direction> {
    return request.put<Direction>(`${BASE}/${id}/kb-doc`, input)
  },
}
