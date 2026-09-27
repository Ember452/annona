import { useCallback, useEffect, useRef, useState } from 'react'
import { UploadIcon } from 'lucide-react'

import { API_BASE_URL } from '@/api/request'
import { knowledgeApi } from '@/api/knowledge'
import ChunkPreviewDialog from '@/components/knowledge/ChunkPreviewDialog'
import DirectionSelector from '@/components/direction/DirectionSelector'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { cn } from '@/lib/utils'
import { toErrorMessage } from '@/lib/errors'
import { useDirections } from '@/hooks/useDirections'
import type { Direction } from '@/types/direction'
import type { KbDoc, KbDocProgress } from '@/types/knowledge'

/**
 * 知识库入口（P1a-05）：上传（必选方向）→ SSE 实时进度（断线降级轮询）→
 * 文档列表（状态徽标 + 进度 + 失败可读原因）→ 分块预览 / 重试处理 / 删除。
 *
 * <p>进度双通道：SSE（原生 EventSource，cookie 同源携带）为主，断开自动降级为
 * 2s 轮询 status——同一形状同一线径（ProgressEvent = KbDocStatusResponse）。
 * 重复上传命中 hash 幂等时给显式提示"零消耗"（决策清单 B-8），不静默成功。
 */
export default function KnowledgePage() {
  const { directions } = useDirections()
  const [docs, setDocs] = useState<KbDoc[]>([])
  const [loading, setLoading] = useState(true)
  const [listError, setListError] = useState<string | null>(null)

  // 上传表单
  const [direction, setDirection] = useState<Direction | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadPct, setUploadPct] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)

  // 进度跟踪：订阅中的文档 id + 最近一次进度
  const [trackingId, setTrackingId] = useState<string | null>(null)
  const [progress, setProgress] = useState<KbDocProgress | null>(null)
  const [previewId, setPreviewId] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<KbDoc | null>(null)
  const [deleting, setDeleting] = useState(false)
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null)

  const loadDocs = useCallback(async () => {
    try {
      setDocs(await knowledgeApi.list())
      setListError(null)
    } catch (e) {
      setListError(toErrorMessage(e, '文档列表加载失败'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadDocs()
  }, [loadDocs])

  const stopPolling = useCallback(() => {
    if (pollTimer.current) {
      clearInterval(pollTimer.current)
      pollTimer.current = null
    }
  }, [])

  const isTerminal = (status: KbDoc['status']) => status === 'READY' || status === 'FAILED'

  /** 处理终态：刷新列表、解除订阅。SSE 与轮询两条通道共用。 */
  const onTerminal = useCallback(
    (status: KbDoc['status'], message: string) => {
      setNotice(
        status === 'READY' ? '文档处理完成，可以查看分块预览。' : `处理失败：${message || '未知原因'}`
      )
      setTrackingId(null)
      setProgress(null)
      stopPolling()
      void loadDocs()
    },
    [loadDocs, stopPolling]
  )

  /** 订阅一个在途文档：SSE 为主，onerror 降级轮询。EventSource 必须带 API_BASE_URL
   * 前缀（与 axios 的 baseURL 同源），跨域部署时裸相对路径会打到前端自己的域。 */
  useEffect(() => {
    if (!trackingId) return
    const docId = trackingId
    const source = new EventSource(`${API_BASE_URL}/api/knowledge/docs/${docId}/progress`)
    source.addEventListener('progress', (ev) => {
      const data = JSON.parse((ev as MessageEvent).data) as KbDocProgress
      setProgress(data)
      if (isTerminal(data.status)) {
        source.close()
        onTerminal(data.status, data.message)
      }
    })
    source.onerror = () => {
      source.close()
      stopPolling()
      pollTimer.current = setInterval(async () => {
        try {
          const status = await knowledgeApi.status(docId)
          setProgress(status)
          if (isTerminal(status.status)) {
            onTerminal(status.status, status.message)
          }
        } catch {
          // 轮询失败保留下一轮；页面级错误由列表加载提示兜底
        }
      }, 2000)
    }
    return () => {
      source.close()
      stopPolling()
    }
  }, [trackingId, onTerminal, stopPolling])

  async function handleUpload() {
    if (!file || !direction?.id || uploading) return
    setUploading(true)
    setUploadPct(0)
    setNotice(null)
    try {
      const result = await knowledgeApi.upload(file, direction.id, setUploadPct)
      setNotice(result.duplicate ? result.message : '已上传，解析与向量化进行中…')
      setFile(null)
      // 终态文档（重复上传命中已 READY/FAILED 的行）没有进度可订阅，不挂空 SSE
      if (!isTerminal(result.status)) {
        setTrackingId(result.id)
        setProgress({ status: result.status, stage: '排队中', processed: 0, total: 0, message: '' })
      }
      await loadDocs()
    } catch (e) {
      setNotice(toErrorMessage(e, '上传失败，请稍后重试'))
    } finally {
      setUploading(false)
    }
  }

  async function handleRetrigger(doc: KbDoc) {
    try {
      await knowledgeApi.revectorize(doc.id)
      setNotice('已重新排队处理…')
      setTrackingId(doc.id)
      setProgress({ status: 'PENDING', stage: '排队中', processed: 0, total: 0, message: '' })
      await loadDocs()
    } catch (e) {
      setNotice(toErrorMessage(e, '重新处理失败'))
    }
  }

  async function performDelete() {
    if (!deleteTarget || deleting) return
    setDeleting(true)
    try {
      await knowledgeApi.remove(deleteTarget.id)
      setDeleteTarget(null)
      await loadDocs()
    } catch (e) {
      setNotice(toErrorMessage(e, '删除失败'))
    } finally {
      setDeleting(false)
    }
  }

  const directionName = (id: string) => directions.find((d) => d.id === id)?.name ?? '未知方向'
  const trackingDoc = docs.find((d) => d.id === trackingId)

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <header>
        <h1 className="font-heading text-2xl font-semibold tracking-tight">知识库</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          上传讲义与教材，方向绑定后作为出题与问答素材；每一次处理进度实时可见。
        </p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle>上传文档</CardTitle>
          <CardDescription>支持 PDF / DOCX / TXT / MD，不超过 50MB；同一文件重复上传零消耗。</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <DirectionSelector value={direction} onChange={setDirection} />
          <label className="flex cursor-pointer items-center gap-3 rounded-lg border border-dashed p-4 text-sm transition-colors hover:border-primary/50">
            <UploadIcon className="size-4 text-muted-foreground" />
            <span className={cn('flex-1 truncate', file ? undefined : 'text-muted-foreground')}>
              {file ? `${file.name}（${formatBytes(file.size)}）` : '点击选择文件'}
            </span>
            <input
              type="file"
              accept=".pdf,.docx,.txt,.md,.markdown,.mdown"
              className="sr-only"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </label>
          {uploading && uploadPct > 0 && uploadPct < 100 && (
            <p className="text-xs text-muted-foreground">上传中 {uploadPct}%</p>
          )}
          <div className="flex items-center gap-3">
            <Button disabled={!file || !direction?.id || uploading} onClick={() => void handleUpload()}>
              {uploading ? '上传中…' : '上传'}
            </Button>
            {trackingDoc && progress && !isTerminal(progress.status) && (
              <span className="text-xs text-muted-foreground">
                {progress.stage}
                {progress.total > 0 ? ` ${progress.processed}/${progress.total}` : ''}
              </span>
            )}
          </div>
          {notice && <p className="text-xs text-muted-foreground">{notice}</p>}
        </CardContent>
      </Card>

      <section className="space-y-3">
        <h2 className="text-sm font-medium">我的文档</h2>
        {loading && <p className="text-sm text-muted-foreground">加载中…</p>}
        {listError && <p className="text-xs text-destructive">{listError}</p>}
        {!loading && !listError && docs.length === 0 && (
          <p className="text-sm text-muted-foreground">还没有文档。上传第一份讲义开始使用。</p>
        )}
        <ul className="space-y-2">
          {docs.map((doc) => (
            <li key={doc.id}>
              <Card>
                <CardContent className="flex items-start gap-3 p-4">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate text-sm font-medium">{doc.name}</span>
                      <StatusBadge status={doc.status} />
                    </div>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {directionName(doc.directionId)} · {formatBytes(doc.fileSize)} ·{' '}
                      {new Date(doc.createdAt).toLocaleString()}
                    </p>
                    {doc.status === 'FAILED' && doc.error && (
                      <p className="mt-1 text-xs text-destructive">{doc.error}</p>
                    )}
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-1">
                    {doc.status === 'READY' && (
                      <Button size="sm" variant="outline" onClick={() => setPreviewId(doc.id)}>
                        分块预览
                      </Button>
                    )}
                    {doc.status === 'FAILED' && (
                      <Button size="sm" variant="outline" onClick={() => void handleRetrigger(doc)}>
                        重新处理
                      </Button>
                    )}
                    <Button
                      size="sm"
                      variant="ghost"
                      className="text-destructive"
                      onClick={() => setDeleteTarget(doc)}
                    >
                      删除
                    </Button>
                  </div>
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      </section>

      <ChunkPreviewDialog docId={previewId} onOpenChange={(open) => !open && setPreviewId(null)} />

      {deleteTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          role="presentation"
          onClick={() => !deleting && setDeleteTarget(null)}
        >
          <Card className="w-80" role="dialog" aria-label="确认删除">
            <CardHeader>
              <CardTitle>删除「{deleteTarget.name}」？</CardTitle>
              <CardDescription>分块与已生成向量一并清理，原文不可恢复。</CardDescription>
            </CardHeader>
            <CardContent className="flex justify-end gap-2">
              <Button variant="outline" size="sm" disabled={deleting} onClick={() => setDeleteTarget(null)}>
                取消
              </Button>
              <Button variant="destructive" size="sm" disabled={deleting} onClick={() => void performDelete()}>
                {deleting ? '删除中…' : '确认删除'}
              </Button>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}

function StatusBadge({ status }: { status: KbDoc['status'] }) {
  const styles: Record<KbDoc['status'], string> = {
    READY: 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400',
    FAILED: 'bg-destructive/15 text-destructive',
    PENDING: 'bg-muted text-muted-foreground',
    PARSING: 'bg-amber-500/15 text-amber-600 dark:text-amber-400',
    CHUNKING: 'bg-amber-500/15 text-amber-600 dark:text-amber-400',
    EMBEDDING: 'bg-amber-500/15 text-amber-600 dark:text-amber-400',
  }
  const labels: Record<KbDoc['status'], string> = {
    READY: '就绪',
    FAILED: '失败',
    PENDING: '排队中',
    PARSING: '解析中',
    CHUNKING: '分块中',
    EMBEDDING: '向量化中',
  }
  return (
    <span className={cn('shrink-0 rounded-full px-2 py-0.5 text-xs', styles[status])}>
      {labels[status]}
    </span>
  )
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
