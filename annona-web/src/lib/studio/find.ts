/**
 * 工作室查找高亮（P2-06，借 🅢 lib/studio/find.ts 的 TreeWalker 思路）：
 * 纯函数只管「查询串 → 安全正则」，DOM 打标在组件里做（jsdom 测纯函数，DOM 细节不测）。
 */

/** 正则元字符转义——查询串按字面量处理，不做模式匹配。 */
export function escapeRegExp(input: string): string {
  return input.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** 构建不区分大小写的全局匹配正则；空查询返回 null（调用方跳过高亮）。 */
export function buildHighlightPattern(query: string): RegExp | null {
  const trimmed = query.trim()
  if (!trimmed) return null
  return new RegExp(escapeRegExp(trimmed), 'gi')
}

/** 预览区 DOM 打标：Text 节点命中的包上 <mark data-studio-find>；返回命中数。 */
export function markMatches(root: HTMLElement, query: string): number {
  clearMarks(root)
  const pattern = buildHighlightPattern(query)
  if (!pattern) return 0
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const targets: Text[] = []
  while (walker.nextNode()) {
    const node = walker.currentNode as Text
    if (node.parentElement?.closest('mark[data-studio-find]')) continue
    if (pattern.test(node.nodeValue ?? '')) targets.push(node)
    pattern.lastIndex = 0
  }
  let hits = 0
  for (const node of targets) {
    const text = node.nodeValue ?? ''
    pattern.lastIndex = 0
    const fragment = document.createDocumentFragment()
    let last = 0
    for (let match = pattern.exec(text); match !== null; match = pattern.exec(text)) {
      if (match.index > last) {
        fragment.appendChild(document.createTextNode(text.slice(last, match.index)))
      }
      const mark = document.createElement('mark')
      mark.setAttribute('data-studio-find', '')
      mark.textContent = match[0]
      fragment.appendChild(mark)
      last = match.index + match[0].length
      hits++
    }
    if (last < text.length) {
      fragment.appendChild(document.createTextNode(text.slice(last)))
    }
    node.parentNode?.replaceChild(fragment, node)
  }
  return hits
}

/** 清掉本容器的全部查找标记（还原为纯文本节点）。 */
export function clearMarks(root: HTMLElement): void {
  root.querySelectorAll('mark[data-studio-find]').forEach((mark) => {
    mark.replaceWith(document.createTextNode(mark.textContent ?? ''))
  })
}
