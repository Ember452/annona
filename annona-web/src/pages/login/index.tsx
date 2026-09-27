import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'

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
import { ROUTES } from '@/constants/routes'
import { useAuth } from '@/stores/auth'
import { cn } from '@/lib/utils'
import { toErrorMessage } from '@/lib/errors'

type AuthMode = 'login' | 'register'

/**
 * 登录/注册页（登录 UI 落地批）。前端不感知 `ANNONA_IDENTITY_MODE`：本页只在
 * local 模式被守卫触达（none 模式 `/api/me` 恒成功直接放行、platform 由反代注入凭据，
 * 见 identity-provider-modes ADR 修订记录）。样式走 P1a-00 设计令牌，不引新依赖；
 * 场景化视觉壳（🅢 auth-scene-shell 的分场景背景视频/图）归 P2-04 主题包，这里刻意
 * 只做可用形态。
 */
export default function LoginPage() {
  const { user, loading, login, register } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // 守卫带来的来源页：登录后回跳，避免一律落首页打断用户手头的操作
  const from = (location.state as { from?: string } | null)?.from

  const [mode, setMode] = useState<AuthMode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (loading) {
    return <div className="min-h-dvh bg-background" aria-busy="true" />
  }
  if (user) {
    return <Navigate to={from ?? ROUTES.HOME} replace />
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (submitting) return
    setError(null)
    setSubmitting(true)
    try {
      if (mode === 'login') {
        await login(email.trim(), password)
      } else {
        await register(email.trim(), password)
      }
      navigate(from ?? ROUTES.HOME, { replace: true })
    } catch (err) {
      setError(toErrorMessage(err, '操作失败，请稍后重试'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-dvh items-center justify-center bg-background p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>{mode === 'login' ? '登录 annona' : '注册 annona'}</CardTitle>
          <CardDescription>
            {mode === 'login'
              ? '使用注册邮箱与密码继续你的学习记录。'
              : '注册即创建本地账号并自动登录。'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-2">
              <Label htmlFor="auth-email">邮箱</Label>
              <Input
                id="auth-email"
                type="email"
                autoComplete="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="auth-password">密码</Label>
              <Input
                id="auth-password"
                type="password"
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={mode === 'register' ? '至少 8 位' : '••••••••'}
              />
            </div>

            {error && (
              <p className="text-xs text-destructive" role="alert">
                {error}
              </p>
            )}

            <Button type="submit" disabled={submitting}>
              {submitting ? '提交中…' : mode === 'login' ? '登录' : '注册并登录'}
            </Button>

            <button
              type="button"
              onClick={() => {
                setMode(mode === 'login' ? 'register' : 'login')
                setError(null)
              }}
              className={cn(
                'text-center text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline',
              )}
            >
              {mode === 'login' ? '还没有账号？去注册' : '已有账号？去登录'}
            </button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
