import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import MarkdownRenderer from './MarkdownRenderer'

/**
 * qa-streaming-adr §决策 6 的同源断言：不装 rehype-raw 时，模型输出的原生 HTML
 * 由构造不渲染——这里把上游 🅢 sanitize 测试里"危险内容必须被清除"的用例搬过来，
 * 换成"按纯文本显示"的口径；刻意支持的功能（链接/表格/代码块）必须存活。
 */
describe('MarkdownRenderer（XSS 面由构造关闭）', () => {
  it('原生 HTML 按纯文本显示：<img onerror> 与 <script> 不产生任何 DOM 元素', () => {
    const { container } = render(
      <MarkdownRenderer
        text={'<img src=x onerror="alert(1)">你好\n\n<script>alert(2)</script>'}
      />,
    )

    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    // 原文可见（当作文本），用户看得出模型输出了什么而不是静默吞掉
    expect(screen.getByText(/<img src=x onerror="alert\(1\)">你好/)).not.toBeNull()
    expect(container.textContent).toContain('<script>alert(2)</script>')
  })

  it('javascript: 协议链接被丢弃协议，不产生可点击的注入点', () => {
    render(<MarkdownRenderer text={'[点我](javascript:alert(1))'} />)

    const link = screen.getByText('点我')
    expect(link.getAttribute('href')).not.toContain('javascript:')
  })

  it('刻意支持的功能存活：https 链接（外链 target=_blank）、GFM 表格与围栏代码块', () => {
    render(
      <MarkdownRenderer
        text={
          '[官网](https://example.com)\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\n```java\nSystem.out.println(1);\n```'
        }
      />,
    )

    const link = screen.getByText('官网')
    expect(link.getAttribute('href')).toBe('https://example.com')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toContain('noopener')
    expect(screen.getByRole('table')).not.toBeNull()
    expect(screen.getByText(/System\.out\.println/)).not.toBeNull()
  })
})
