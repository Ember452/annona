/**
 * 与后端 `io.annona.modules.plan.dto` 对齐的手写占位类型（P2-06，plan-module-adr）。
 */

/** 任务状态（chk_plan_task_status）。 */
export type PlanTaskStatus = 'PENDING' | 'DONE'

/** 任务分类（chk_plan_task_category）。 */
export type PlanTaskCategory = 'study' | 'project' | 'review' | 'exercise'

/** 计划任务。 */
export interface PlanTask {
  id: string
  planId: string
  title: string
  description: string
  category: PlanTaskCategory
  /** high | normal | low。 */
  priority: string
  status: PlanTaskStatus
  targetMinutes: number
  /** 打卡联动已累计分钟（只由后端联动监听器维护）。 */
  progressMinutes: number
  /** AI = 拆分生成 | MANUAL = 手动追加。 */
  source: 'AI' | 'MANUAL'
}

/** 计划详情（工作室装载形状）。 */
export interface PlanDetail {
  id: string
  title: string
  directionId: string | null
  document: string
  /** 文档与最近拆分指纹不一致——建议重拆。 */
  stale: boolean
  tasks: PlanTask[]
  updatedAt: string
}

/** 计划列表项。 */
export interface PlanSummary {
  id: string
  title: string
  directionId: string | null
  totalTasks: number
  doneTasks: number
  updatedAt: string
}

/** POST /api/plans/{id}/split 响应。 */
export interface SplitResponse {
  tasks: PlanTask[]
  /** false = 指纹未变短路返回（没花 token）。 */
  applied: boolean
}
