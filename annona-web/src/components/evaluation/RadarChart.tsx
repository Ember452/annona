import { radarAxes, radarFrame, radarPolygon } from '@/lib/evaluationView'
import type { QuestionEvaluation } from '@/types/evaluation'

/**
 * 手绘 SVG 雷达图（每轴一道题，值=该题可评分槽位均值）。零图表依赖——批 3 T4 定案：
 * 包内无图表库、P0-09 排除 recharts，画 N 轴多边形无需引库。geometry 来自 lib/evaluationView
 * 纯函数（可测），本组件只做渲染。null 轴落圆心（无分不假装）。
 */
export function RadarChart({ questions, size = 220 }: { questions: QuestionEvaluation[]; size?: number }) {
  const axes = radarAxes(questions)
  const center = size / 2
  const points = radarPolygon(axes, size)
  const frame = radarFrame(axes, size)
  if (axes.length === 0) {
    return <p className="text-sm text-muted-foreground">暂无可展示的逐题分数</p>
  }
  const toStr = (pts: { x: number; y: number }[]) =>
    pts.map((p) => `${p.x},${p.y}`).join(' ')
  // 轴标签放在外沿稍外（半径 ×1.12），沿角度定位；单轴时贴顶
  const labelRadius = size / 2 - 20 + 12
  return (
    <svg
      viewBox={`0 0 ${size} ${size}`}
      width={size}
      height={size}
      role="img"
      aria-label="逐题得分雷达图"
      data-testid="radar-chart"
    >
      <polygon points={toStr(frame)} fill="none" stroke="currentColor"
        strokeOpacity={0.2} />
      {frame.map((p, i) => (
        <line key={`spoke-${i}`} x1={center} y1={center} x2={p.x} y2={p.y}
          stroke="currentColor" strokeOpacity={0.15} />
      ))}
      <polygon points={toStr(points)} fill="currentColor" fillOpacity={0.18}
        stroke="currentColor" strokeWidth={1.5} />
      {axes.map((axis, i) => {
        const angle = -Math.PI / 2 + (i * 2 * Math.PI) / axes.length
        const lx = center + labelRadius * Math.cos(angle)
        const ly = center + labelRadius * Math.sin(angle)
        return (
          <text key={`label-${i}`} x={lx} y={ly} fontSize={10} textAnchor="middle"
            dominantBaseline="middle" className="fill-muted-foreground">
            {axis.label}
          </text>
        )
      })}
    </svg>
  )
}
