import type { SessionView, SlotView } from '@/types/interview'

/**
 * 面试作答流程的纯状态逻辑（P1b-05 前端；借 🅖"提示逻辑抽纯函数便于测试"形态）。
 * reducer 只管迁移与回滚，网络调用留在组件里——vitest 断言的是"失败不清草稿"这类
 * 用户可感知行为，不是 axios。
 */

export type FlowPhase = 'form' | 'answering' | 'submitted'

export interface FlowState {
  phase: FlowPhase
  view: SessionView | null
  /** 当前主问题位次（0 起，与后端 currentIndex 同口径）。 */
  order: number
  /** 未提交草稿：key 为 `${questionId}:${followUpIndex}`。失败重试不丢用户输入。 */
  drafts: Record<string, string>
  error: string | null
  busy: boolean
}

export type FlowAction =
  | { type: 'OPEN'; view: SessionView }
  | { type: 'DRAFT'; slotKey: string; text: string }
  | { type: 'ADVANCE' }
  | { type: 'BUSY'; busy: boolean }
  | { type: 'FAIL'; message: string }
  | { type: 'SUBMITTED'; view: SessionView }
  | { type: 'RESET' }

export const initialFlow: FlowState = {
  phase: 'form',
  view: null,
  order: 0,
  drafts: {},
  error: null,
  busy: false,
}

export function slotKey(slot: Pick<SlotView, 'questionId' | 'followUpIndex'>): string {
  return `${slot.questionId}:${slot.followUpIndex}`
}

/** 按主问题位次分组（视图展平序 -> 步进序）；空会话返回空数组。 */
export function slotsByOrder(view: SessionView | null): SlotView[][] {
  if (!view) return []
  const groups = new Map<number, SlotView[]>()
  for (const slot of view.slots) {
    const bucket = groups.get(slot.order)
    if (bucket) {
      bucket.push(slot)
    } else {
      groups.set(slot.order, [slot])
    }
  }
  return [...groups.entries()].sort((a, b) => a[0] - b[0]).map(([, slots]) => slots)
}

export function flowReducer(state: FlowState, action: FlowAction): FlowState {
  switch (action.type) {
    case 'OPEN': {
      // 恢复位以服务端 currentIndex 为准（断线重进回到当前主题，不是第一题）
      return { ...state, phase: 'answering', view: action.view,
        order: Math.min(action.view.currentIndex, Math.max(action.view.totalCount - 1, 0)),
        drafts: {}, error: null }
    }
    case 'DRAFT':
      return { ...state, drafts: { ...state.drafts, [action.slotKey]: action.text } }
    case 'ADVANCE': {
      const last = Math.max(state.view?.totalCount ?? 1, 1) - 1
      return { ...state, order: Math.min(state.order + 1, last), error: null }
    }
    case 'BUSY':
      return { ...state, busy: action.busy }
    case 'FAIL':
      // 失败只记文案：草稿、位次、phase 全部保留——用户改一改就能重试
      return { ...state, busy: false, error: action.message }
    case 'SUBMITTED':
      return { ...state, phase: 'submitted', view: action.view, busy: false, error: null }
    case 'RESET':
      return { ...initialFlow }
    default:
      return state
  }
}
