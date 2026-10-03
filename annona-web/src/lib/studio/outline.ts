/**
 * 工作室大纲面板的纯函数层（P2-06，借 🅢 studio/outline.ts 的 regex 提取 + 拖拽重排）：
 * 大纲 = 逐行扫 markdown 标题（#{1,6}），重排 = 交换两个标题块在文档中的行序。
 */

export interface OutlineEntry {
  /** 1..6。 */
  level: number
  text: string
  /** 文档中的行号（0 起）。 */
  line: number
}

/** 逐行提取标题大纲（跳过代码围栏内的 # 行——正文里的 ``` 块不算结构）。 */
export function extractOutline(document: string): OutlineEntry[] {
  const entries: OutlineEntry[] = []
  let inFence = false
  document.split('\n').forEach((line, index) => {
    if (/^\s*```/.test(line)) {
      inFence = !inFence
      return
    }
    if (inFence) return
    const match = /^(#{1,6})\s+(.+?)\s*$/.exec(line)
    if (match) {
      entries.push({ level: match[1].length, text: match[2], line: index })
    }
  })
  return entries
}

/**
 * 交换文档中两个行号的内容（大纲拖拽重排的落点：整行互换——标题行即块的锚，
 * 块体跟随标题属后续演进，v1 与上游 swap 行为一致）。
 * 行号越界/相同则原样返回。
 */
export function swapLines(document: string, lineA: number, lineB: number): string {
  if (lineA === lineB || lineA < 0 || lineB < 0) return document
  const lines = document.split('\n')
  if (lineA >= lines.length || lineB >= lines.length) return document
  ;[lines[lineA], lines[lineB]] = [lines[lineB], lines[lineA]]
  return lines.join('\n')
}
