import { useEffect, useState } from 'react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { knowledgeApi } from '@/api/knowledge'
import type { KbDoc } from '@/types/knowledge'
import { toErrorMessage } from '@/lib/errors'

/**
 * 知识库文档选择对话框（P1a-05）：DirectionSelector 的"绑定知识库"从 UUID 手输
 * 占位升级为真实选择器。只读列表 + 状态标识，READY 文档可选、处理中禁用——
 * 绑定未就绪的文档会让方向的出题素材为空。
 */
interface KbDocPickerDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  binding: boolean
  onPick: (docId: string, docName: string) => void
}

export default function KbDocPickerDialog({ open, onOpenChange, binding, onPick }: KbDocPickerDialogProps) {
  const [docs, setDocs] = useState<KbDoc[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setLoading(true)
    setError(null)
    knowledgeApi
      .list()
      .then(setDocs)
      .catch((e) => setError(toErrorMessage(e, '文档列表加载失败')))
      .finally(() => setLoading(false))
  }, [open])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[70vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>选择要绑定的知识库文档</DialogTitle>
          <DialogDescription>绑定后该方向将以这份文档为素材出题；仅"就绪"状态可选。</DialogDescription>
        </DialogHeader>
        {loading && <p className="py-4 text-sm text-muted-foreground">加载中…</p>}
        {error && <p className="py-2 text-xs text-destructive">{error}</p>}
        {!loading && !error && docs.length === 0 && (
          <p className="py-4 text-sm text-muted-foreground">
            知识库还没有文档。先去「知识库」入口上传一份讲义或教材。
          </p>
        )}
        <ul className="space-y-1">
          {docs.map((doc) => (
            <li key={doc.id}>
              <button
                type="button"
                disabled={binding || doc.status !== 'READY'}
                className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm transition-colors hover:bg-muted/60 disabled:cursor-not-allowed disabled:opacity-50"
                onClick={() => onPick(doc.id, doc.name)}
              >
                <span className="flex-1 truncate">{doc.name}</span>
                <span className="shrink-0 text-xs text-muted-foreground">{statusLabel(doc.status)}</span>
              </button>
            </li>
          ))}
        </ul>
      </DialogContent>
    </Dialog>
  )
}

function statusLabel(status: KbDoc['status']): string {
  switch (status) {
    case 'READY':
      return '就绪'
    case 'FAILED':
      return '失败'
    case 'PENDING':
      return '排队中'
    case 'PARSING':
      return '解析中'
    case 'CHUNKING':
      return '分块中'
    default:
      return '向量化中'
  }
}
