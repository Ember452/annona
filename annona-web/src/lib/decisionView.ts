import type { DecisionTrace } from '@/types/decision'

/**
 * 可解释面板的展示纯函数（IO 不在这里，组件只做取数与渲染）。规则键 → 人话标签、
 * 驳回后的本地状态迁移（reducer 单独可测，对齐"reducer 单独测"先例）。
 */

/** 规则键到中文短标签；未知键原样回显（后端新增规则时前端不崩，只是少一层美化）。 */
export function ruleLabel(ruleKey: string): string {
  switch (ruleKey) {
    case 'FORGETTING_CURVE': return '遗忘曲线'
    case 'WEAK_DIRECTION': return '薄弱方向'
    case 'SAMPLE_GUARD': return '样本保护'
    case 'VERSION_BASELINE': return '换模型基线'
    case 'SELF_REPORTED': return '自报数据'
    case 'NO_STUDY_RECORD': return '仅面试表现'
    case 'REMIND_REVIEW': return '复习提醒'
    default: return ruleKey
  }
}

/**
 * 驳回一条留痕后的新列表（不可变）：命中 traceId 的项 rejectedBy 置 'USER'，其余原样。
 * 纯函数，组件据此本地即时反馈（后端 reject 已落库，这里是乐观更新）。
 */
export function applyRejection(traces: DecisionTrace[], traceId: string): DecisionTrace[] {
  return traces.map((t) => (t.traceId === traceId ? { ...t, rejectedBy: 'USER' } : t))
}

/** 面板空态文案：无决策记录（默认策略出题）——诚实呈现，不假装有话可说。 */
export function emptyHint(traces: DecisionTrace[]): string | null {
  return traces.length === 0 ? '本次面试未记录决策依据（由默认策略出题）' : null
}

/** 是否已被用户驳回（控制按钮禁用态）。 */
export function isRejected(trace: DecisionTrace): boolean {
  return trace.rejectedBy === 'USER'
}

/** 一场面试的决策摘要（首页"最近 N 场"分组单元）。 */
export interface SessionDecisionGroup {
  sessionId: string
  traces: DecisionTrace[]
}

/**
 * 按 sessionId 分组（保持首次出现顺序；recent 已按时间倒序，故会话按最近优先排）。
 * 同一会话的多条留痕聚到一组，首页据此逐场展示理由。
 */
export function groupBySession(traces: DecisionTrace[]): SessionDecisionGroup[] {
  const order: string[] = []
  const bySession = new Map<string, DecisionTrace[]>()
  for (const t of traces) {
    if (!bySession.has(t.sessionId)) {
      bySession.set(t.sessionId, [])
      order.push(t.sessionId)
    }
    bySession.get(t.sessionId)!.push(t)
  }
  return order.map((sessionId) => ({ sessionId, traces: bySession.get(sessionId)! }))
}
