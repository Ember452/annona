import { useEffect, useRef, useState } from 'react'
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
 *
 * <p>P1a-08 起兼作引用跳转的落点：{@code highlightChunkIndex} 存在时滚动定位到该块并
 * 高亮（qa 的引用角标 → 原文段落，验收原文）。分块算法版本陈旧（doc.analyzerVersion ≠
 * currentAnalyzerVersion）时提示重建——char-v2 之后旧块仍是旧切法，不提示会出现
 * "同库两种块质量"而用户无从解释（复用批 2 的 revectorize 入口）。
 */
interface ChunkPreviewDialogProps {
  docId: string | null
  onOpenChange: (open: boolean) => void
  /** 需要定位高亮的分块序号；null = 不定位。 */
  highlightChunkIndex?: number | null
}

export default function ChunkPreviewDialog({ docId, onOpenChange, highlightChunkIndex }: ChunkPreviewDialogProps) {
  const [detail, setDetail] = useState<KbDocDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rebuilding, setRebuilding] = useState(false)
  const highlightedRef = useRef<HTMLLIElement | null>(null)

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

  // 高亮块滚动定位：等 detail 渲染完成后再滚（依赖两者）
  useEffect(() => {
    if (detail != null && highlightChunkIndex != null) {
      highlightedRef.current?.scrollIntoView({ block: 'center' })
    }
  }, [detail, highlightChunkIndex])

  const stale = detail != null && detail.doc.analyzerVersion !== detail.currentAnalyzerVersion

  const rebuild = async () => {
    if (!docId) return
    setRebuilding(true)
    try {
      await knowledgeApi.revectorize(docId)
      onOpenChange(false)
    } catch (e) {
      setError(toErrorMessage(e, '重建发起失败'))
    } finally {
      setRebuilding(false)
    }
  }

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
        {stale && (
          <p className="flex items-center gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-700">
            <span>
              分块算法已更新（该文档为 {detail.doc.analyzerVersion}，当前 {detail.currentAnalyzerVersion}），建议重建。
            </span>
            <button
              type="button"
              onClick={() => void rebuild()}
              disabled={rebuilding}
              className="shrink-0 rounded border border-amber-600/50 px-2 py-0.5 font-medium hover:bg-amber-500/20 disabled:opacity-50"
            >
              {rebuilding ? '发起中…' : '重建'}
            </button>
          </p>
        )}
        {loading && <p className="py-4 text-sm text-muted-foreground">加载中…</p>}
        {error && <p className="py-2 text-xs text-destructive">{error}</p>}
        <ol className="space-y-3">
          {detail?.chunks.map((chunk) => {
            const highlighted = chunk.index === highlightChunkIndex
            return (
              <li
                key={chunk.index}
                ref={highlighted ? highlightedRef : undefined}
                className={`rounded-lg border p-3 ${
                  highlighted ? 'border-primary bg-primary/5' : 'bg-card'
                }`}
              >
                <div className="mb-1 flex items-baseline gap-2 text-xs text-muted-foreground">
                  <span className="font-medium text-foreground">#{chunk.index}</span>
                  {chunk.headingPath && <span className="truncate">{chunk.headingPath}</span>}
                  <span className="ml-auto shrink-0">
                    偏移 {chunk.charStart}–{chunk.charEnd} · {chunk.charEnd - chunk.charStart} 字
                  </span>
                </div>
                <p className="whitespace-pre-wrap text-sm leading-relaxed">{chunk.content}</p>
              </li>
            )
          })}
        </ol>
      </DialogContent>
    </Dialog>
  )
}
