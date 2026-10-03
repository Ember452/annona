/** 个人资料视图（与后端 ProfileResponse 对齐，P2-07）。 */
export interface Profile {
  id: string
  email: string
  nickname: string | null
  bio: string | null
  timezone: string
  themeKey: string
  /** 当前头像对象 key；null = 未设置自定义头像 */
  avatarObjectKey: string | null
}

/** PATCH /api/me/profile 请求体：null 字段不动。 */
export interface UpdateProfileInput {
  nickname?: string | null
  bio?: string | null
}

/** 头像上传/回滚响应（与后端 AvatarUploadResponse 对齐）。 */
export interface AvatarUpdateResult {
  success: boolean
  previousKey: string | null
}
