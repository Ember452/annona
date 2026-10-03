import { useCallback, useEffect, useRef, useState } from 'react'

import { accountApi } from '@/api/account'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { toErrorMessage } from '@/lib/errors'
import type { Profile } from '@/types/account'

/** 上传前置校验的三条硬规则必须与后端 AvatarService 同口径（双保险，服务端为准）。 */
const MAX_AVATAR_BYTES = 5 * 1024 * 1024
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp']

/**
 * 个人主页与设置（P2-07）：资料编辑 + 头像上传/历史回滚。
 *
 * 模型配置入口在 /interview 侧既有页；数据导出入口随跨模块导出方案落地后再挂
 * （docs/plans/DATA_EXPORT_PLAN.md，P2-07 裁决拆分）。头像字节经 GET /api/me/avatar
 * 代理回读（img src 自带同源 Cookie），更新后用 key 作 cache-bust 参数。
 */
export default function ProfilePage() {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [nickname, setNickname] = useState('')
  const [bio, setBio] = useState('')
  const [history, setHistory] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const refresh = useCallback(async () => {
    try {
      const [p, h] = await Promise.all([accountApi.getProfile(), accountApi.avatarHistory()])
      setProfile(p)
      setNickname(p.nickname ?? '')
      setBio(p.bio ?? '')
      setHistory(h)
      setError(null)
    } catch (e) {
      setError(toErrorMessage(e, '资料加载失败'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  async function saveProfile() {
    setSaving(true)
    try {
      const updated = await accountApi.updateProfile({ nickname, bio })
      setProfile(updated)
      setNotice('资料已保存')
      setError(null)
    } catch (e) {
      setError(toErrorMessage(e, '保存失败'))
    } finally {
      setSaving(false)
    }
  }

  async function onPickAvatar(file: File) {
    if (file.size > MAX_AVATAR_BYTES) {
      setError('头像图片过大（上限 5MB）')
      return
    }
    if (!ALLOWED_TYPES.includes(file.type)) {
      setError('头像仅支持 jpg/png/webp')
      return
    }
    try {
      await accountApi.uploadAvatar(file)
      setNotice('头像已更新')
      await refresh()
    } catch (e) {
      setError(toErrorMessage(e, '头像上传失败'))
    }
  }

  async function rollbackAvatar() {
    try {
      await accountApi.rollbackAvatar()
      setNotice('已回滚到上一张头像')
      await refresh()
    } catch (e) {
      setError(toErrorMessage(e, '头像回滚失败'))
    }
  }

  if (loading) {
    return <p className="text-sm text-muted-foreground">资料加载中…</p>
  }

  return (
    <section className="mx-auto flex w-full max-w-3xl flex-col gap-6">
      <div>
        <h1 className="font-heading text-2xl font-semibold tracking-tight">个人主页与设置</h1>
        <p className="mt-1 text-sm text-muted-foreground">{profile?.email}</p>
      </div>

      {error && (
        <p role="alert" className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </p>
      )}
      {notice && !error && (
        <p className="rounded-lg bg-primary/10 px-3 py-2 text-sm text-primary">{notice}</p>
      )}

      <Card>
        <CardHeader>
          <CardTitle>头像</CardTitle>
          <CardDescription>上传 jpg/png/webp（≤5MB）；换下的上一张可随时回滚。</CardDescription>
        </CardHeader>
        <CardContent className="flex items-center gap-4">
          {profile?.avatarObjectKey ? (
            <img
              src={accountApi.avatarImageUrl(profile.avatarObjectKey)}
              alt="当前头像"
              className="size-16 rounded-full border object-cover"
            />
          ) : (
            <span className="flex size-16 items-center justify-center rounded-full bg-muted text-lg font-medium text-muted-foreground">
              {(profile?.nickname ?? profile?.email ?? '?').slice(0, 1).toUpperCase()}
            </span>
          )}
          <div className="flex flex-col gap-2">
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
              >
                上传头像
              </Button>
              {history.length > 0 && (
                <Button variant="ghost" size="sm" onClick={() => void rollbackAvatar()}>
                  回滚上一张
                </Button>
              )}
            </div>
            <input
              ref={fileInputRef}
              type="file"
              accept={ALLOWED_TYPES.join(',')}
              className="hidden"
              onChange={(event) => {
                const file = event.target.files?.[0]
                if (file) void onPickAvatar(file)
                event.target.value = ''
              }}
            />
            {history.length === 0 && (
              <span className="text-xs text-muted-foreground">暂无历史头像</span>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>资料</CardTitle>
          <CardDescription>昵称不超过 64 字符，简介不超过 500 字符。</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="nickname">昵称</Label>
            <Input
              id="nickname"
              value={nickname}
              maxLength={64}
              onChange={(event) => setNickname(event.target.value)}
              placeholder="怎么称呼你"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="bio">简介</Label>
            <Input
              id="bio"
              value={bio}
              maxLength={500}
              onChange={(event) => setBio(event.target.value)}
              placeholder="一句话介绍现在的目标"
            />
          </div>
          <div>
            <Button onClick={() => void saveProfile()} disabled={saving}>
              {saving ? '保存中…' : '保存资料'}
            </Button>
          </div>
        </CardContent>
      </Card>
    </section>
  )
}
