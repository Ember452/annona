import { request } from '@/api/request'

/**
 * identity 后端（P1a-01/02）四端点封装。会话靠 HttpOnly Cookie（`ANNONA_SESSION`）
 * 承载——同源请求由浏览器自动携带，本文件不碰任何 token 存储。
 *
 * <p>none（单机免登录）模式下 `/api/me` 恒成功、登录页不会被触达；platform 模式凭据
 * 由受信反代注入。三模式共用本文件，无需感知 `ANNONA_IDENTITY_MODE`
 * （identity-provider-modes ADR 修订记录 2026-09-27）。
 */

/** 与后端 `io.annona.spi.dto.Principal` 对齐（GET /api/me）。displayName 由
 * `CurrentPrincipalArgumentResolver` 兜底为 email（provider 级 null 不会出现在本响应），类型留 null 是防御性宽放。 */
export interface AuthUser {
  id: string
  displayName: string | null
  roles: string[]
}

/** 与后端 `AuthUserResponse` 对齐（POST /api/auth/register）。 */
export interface RegisterResponse {
  id: string
  email: string
  role: string
}

const BASE = '/api/auth'

export const authApi = {
  /** GET /api/me：无有效会话时后端返回 Result.error(1004)，拦截器转 rejected Promise。 */
  me(): Promise<AuthUser> {
    return request.get<AuthUser>('/api/me')
  },

  /** POST /api/auth/login：成功即下发会话 Cookie。 */
  login(email: string, password: string): Promise<void> {
    return request.post<void>(`${BASE}/login`, { email, password })
  },

  /** POST /api/auth/register：仅建号不发会话；自动登录由调用侧（AuthContext）续接。 */
  register(email: string, password: string): Promise<RegisterResponse> {
    return request.post<RegisterResponse>(`${BASE}/register`, { email, password })
  },

  /** POST /api/auth/logout：服务端删会话 + 清 Cookie；未登录调用也返回成功。 */
  logout(): Promise<void> {
    return request.post<void>(`${BASE}/logout`)
  },
}
