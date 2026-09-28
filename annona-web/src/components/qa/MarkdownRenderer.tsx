import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/**
 * qa 回答渲染器（qa-streaming-adr §决策 6）：react-markdown + remark-gfm。
 *
 * <p>**刻意不装 rehype-raw**：模型输出的原生 HTML 由构造不渲染（当作纯文本），
 * XSS 面直接关闭，替代上游 🅢 的 sanitize 白名单方案（同源断言见 MarkdownRenderer.test.tsx）。
 * 链接协议由 react-markdown 默认 urlTransform 收敛为 http/https/mailto 等安全协议，
 * javascript: 一律丢弃；外链统一 target=_blank + rel=noopener。
 *
 * <p>流式期间每帧全量重 parse：增量 parse 会撕裂 markdown 结构（ADR §决策 3），
 * 单次完整 parse 的代价在回答长度量级（数百字）可接受。
 */
export default function MarkdownRenderer({ text }: { text: string }) {
  return (
    <div className="text-sm leading-relaxed [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_pre]:overflow-x-auto [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: (props) => <a {...props} target="_blank" rel="noopener noreferrer" />,
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  )
}
