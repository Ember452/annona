import { useEffect, useState } from 'react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { knowledgeApi } from '@/api/knowledge'
import type { KbDocDetail } from '@/types/knowledge'
import { toErrorMessage } from '@/lib/errors'

/**
 * 分块预览对话框（决策清单 B-10）：READY 后用户肉眼校验分块质量的第一手数据——
 * 标题路径、原文偏移、每块内容。这些浏览行为同时是分块算法迭代的第一手反馈。
 */
interface ChunkPreviewDialogProps {
  docId: string | null
  onOpenChange: (open: boolean) => void
}

export default function ChunkPreviewDialog({ docId, onOpenChange }: ChunkPreviewDialogProps) {
  const [detail, setDetail] = useState<KbDocDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!docId) return
    setLoading(true)
    setError(null)
    setDetail(null)
    knowledgeApi
      .detail(docId)
      .then(setDetail)
      .catch((e) => setError(toErrorMessage(e, '分块加载失败')))
      .finally(() => setLoading(false))
  }, [docId])

  return (
    <Dialog open={docId !== null} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[80vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>分块预览{detail ? `· ${detail.doc.name}` : ''}</DialogTitle>
          <DialogDescription>
            {detail && detail.doc.status === 'READY'
              ? `共 ${detail.chunks.length} 块；偏移指向清洗后原文位置，供引用定位。`
              : detail && detail.doc.status === 'FAILED'
                ? '文档处理失败，没有可预览的分块。'
                : '文档尚未就绪，处理完成后才能看到分块。'}
          </DialogDescription>
        </DialogHeader>
        {loading && <p className="py-4 text-sm text-muted-foreground">加载中…</p>}
        {error && <p className="py-2 text-xs text-destructive">{error}</p>}
        <ol className="space-y-3">
          {detail?.chunks.map((chunk) => (
            <li key={chunk.index} className="rounded-lg border bg-card p-3">
              <div className="mb-1 flex items-baseline gap-2 text-xs text-muted-foreground">
                <span className="font-medium text-foreground">#{chunk.index}</span>
                {chunk.headingPath && <span className="truncate">{chunk.headingPath}</span>}
                <span className="ml-auto shrink-0">
                  偏移 {chunk.charStart}–{chunk.charEnd} · {chunk.charEnd - chunk.charStart} 字
                </span>
              </div>
              <p className="whitespace-pre-wrap text-sm leading-relaxed">{chunk.content}</p>
            </li>
          ))}
        </ol>
      </DialogContent>
    </Dialog>
  )
}
