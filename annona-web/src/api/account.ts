import { request } from '@/api/request'
import type { AvatarUpdateResult, Profile, UpdateProfileInput } from '@/types/account'

/**
 * 个人资料与头像（P2-07）端点封装。
 *
 * 头像展示走 {@link avatarImageUrl}（GET /api/me/avatar 代理回读字节，浏览器 img 自带
 * 同源 Cookie）；presign 直链随数据导出方案另行落地。Result 拆包与错误归一在
 * `./request` 拦截器统一处理。
 */
const BASE = '/api/me'

export const accountApi = {
  /** GET /api/me/profile：当前用户资料视图。 */
  getProfile(): Promise<Profile> {
    return request.get<Profile>(`${BASE}/profile`)
  },

  /** PATCH /api/me/profile：部分更新（null 不动）。 */
  updateProfile(input: UpdateProfileInput): Promise<Profile> {
    return request.patch<Profile>(`${BASE}/profile`, input)
  },

  /** POST /api/me/avatar：multipart 上传（字段名 file，≤5MB，jpg/png/webp）。 */
  uploadAvatar(file: File): Promise<AvatarUpdateResult> {
    const form = new FormData()
    form.append('file', file)
    return request.post<AvatarUpdateResult>(`${BASE}/avatar`, form)
  },

  /** GET /api/me/avatar/history：历史头像 key 列表（最近优先）。 */
  avatarHistory(): Promise<string[]> {
    return request.get<string[]>(`${BASE}/avatar/history`)
  },

  /** POST /api/me/avatar/rollback：当前头像与最近历史交换；无历史 → 2008。 */
  rollbackAvatar(): Promise<AvatarUpdateResult> {
    return request.post<AvatarUpdateResult>(`${BASE}/avatar/rollback`)
  },

  /** 头像字节端点的 URL，直接给 img src（加 cache-bust 参数在更新后强制刷新）。 */
  avatarImageUrl(version?: string | null): string {
    const base = `${BASE}/avatar`
    return version ? `${base}?v=${encodeURIComponent(version)}` : base
  },
}
