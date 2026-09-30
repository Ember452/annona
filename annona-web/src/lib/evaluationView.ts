import type { EvaluationReport, QuestionEvaluation, ReportStatus } from '@/types/evaluation'

/** 雷达图一轴：一道主问题（含其追问聚合）的分值；null = 该题组全降级，无分可画。 */
export interface RadarAxis {
  label: string
  value: number | null
}

/**
 * 按 questionId 聚合逐题明细为雷达轴（保持首次出现顺序）：同一主问题与其追问取
 * 可评分槽位的均值；全降级（无可评分）→ 该轴 value=null。报告页与雷达图共用（纯函数可测）。
 */
export function radarAxes(questions: QuestionEvaluation[]): RadarAxis[] {
  const order: string[] = []
  const byQuestion = new Map<string, QuestionEvaluation[]>()
  for (const q of questions) {
    if (!byQuestion.has(q.questionId)) {
      byQuestion.set(q.questionId, [])
      order.push(q.questionId)
    }
    byQuestion.get(q.questionId)!.push(q)
  }
  return order.map((questionId, index) => {
    const group = byQuestion.get(questionId)!
    const scored = group.filter((g) => g.score != null && !g.fallbackUsed)
    const value = scored.length === 0
      ? null
      : Math.round(scored.reduce((sum, g) => sum + (g.score ?? 0), 0) / scored.length)
    return { label: `第${index + 1}题`, value }
  })
}

/** 雷达图上一个顶点（SVG 坐标）。 */
export interface RadarPoint {
  x: number
  y: number
}

/**
 * 把轴值映射为半径 size 的雷达多边形顶点：从正上方（-90°）起顺时针均分，
 * value/100 缩放到半径；null 视作 0（落在圆心）。几何纯计算，golden 可钉。
 */
export function radarPolygon(axes: RadarAxis[], size = 200): RadarPoint[] {
  const center = size / 2
  const radius = size / 2 - 20
  const n = axes.length
  if (n === 0) return []
  return axes.map((axis, i) => {
    const angle = -Math.PI / 2 + (i * 2 * Math.PI) / n
    const ratio = (axis.value ?? 0) / 100
    return {
      x: round(center + radius * ratio * Math.cos(angle)),
      y: round(center + radius * ratio * Math.sin(angle)),
    }
  })
}

/** 各轴满值时的外沿顶点（画参考多边形用）。 */
export function radarFrame(axes: RadarAxis[], size = 200): RadarPoint[] {
  return radarPolygon(axes.map((a) => ({ label: a.label, value: 100 })), size)
}

/** 轮询决策：PENDING/RUNNING 继续，DONE/FAILED 终止（报告页据此起停定时器）。 */
export function shouldPoll(status: ReportStatus | undefined): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

/** 综合分显示：null（未出分/全降级）渲染为占位串，不给假 0。 */
export function formatScore(report: Pick<EvaluationReport, 'compositeScore' | 'status'>): string {
  if (report.compositeScore == null) return '—'
  return String(report.compositeScore)
}

function round(v: number): number {
  return Math.round(v * 100) / 100
}
