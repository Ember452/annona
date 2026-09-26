/**
 * 与后端 `io.annona.shared.direction.dto` 对齐的手写占位类型（P1a-03）。
 *
 * <p>同 `types/api.ts` 的先例：`pnpm gen:api` 生效后由 `api.gen.ts` 接管，
 * 本文件届时删除。
 */

/** direction.origin 取值（V1 基线 chk_direction_origin 约束的同名子集）。 */
export type DirectionOrigin =
  | 'SKILL_BUILTIN'
  | 'KNOWLEDGE_BASE'
  | 'USER_CUSTOM'
  | 'JD_PARSED'

/** direction.status 取值；列表接口只返回 ACTIVE。 */
export type DirectionStatus = 'ACTIVE' | 'ARCHIVED'

/** 与 DirectionResponse 对齐（UUID 序列化为字符串，kbDocId 未绑定时为 null）。 */
export interface Direction {
  id: string
  key: string
  name: string
  origin: DirectionOrigin
  /** 绑定的知识库文档 id；未绑定为 null。 */
  kbDocId: string | null
  status: DirectionStatus
  /** ISO-8601 时间串（后端 timestamptz）。 */
  createdAt: string
}

/** POST /api/directions 请求体：key 可选，缺省由后端按名称推导（ASCII slug 化 / custom- 前缀）。 */
export interface CreateDirectionInput {
  /** 1–128 字符。 */
  name: string
  /** 显式指定时需匹配 ^[a-z0-9]+(-[a-z0-9]+)*$ 且 ≤64 字符。 */
  key?: string
}

/** PUT /api/directions/{id}/kb-doc 请求体（P1a-05 接入真实 kb_doc 表前只做 UUID 格式校验）。 */
export interface BindKbDocInput {
  kbDocId: string
}
