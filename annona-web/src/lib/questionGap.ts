/**
 * 题库缺口与容量的纯函数（P1b-03，借 🅖 questionGenerationStatus.ts /
 * interviewCapacity.ts 的"提示逻辑抽纯函数便于测试"形态）。
 */

/** 每题可用追问数（题干非空才算——与后端容量口径一致）。 */
export function usableFollowUpCount(
  followUps: { question: string | null }[] | null | undefined,
): number {
  return (followUps ?? []).filter((f) => f.question != null && f.question.trim() !== '').length
}

/** 追问缺口徽章文案：实际 < 目标时返回提示串，否则 null。 */
export function followUpGapWarning(actual: number, target: number): string | null {
  return actual < target ? `追问不足：实际 ${actual} / 目标 ${target}` : null
}

/**
 * 容量不足的建设性文案（借 🅖 getStrictCapacityMessage）：用户期望的追问档位不可选时，
 * 在可支撑的档位里找最高者，告诉用户"当前题量下每题最多可严格保证 M 个追问"；
 * 题量本身不够（tier0 可用数 < 主问题数）时给先生成/启用的提示。
 * 期望档位可选（或 options 为空）返回 null。
 */
export function strictCapacityMessage(
  options: { followUpCount: number; availableQuestionCount: number; selectable: boolean }[],
  mainQuestionCount: number,
  desiredFollowUps: number,
): string | null {
  if (options.length === 0) {
    return null
  }
  const desired = options.find((o) => o.followUpCount === desiredFollowUps)
  if (desired?.selectable) {
    return null
  }
  const totalAvailable = options.find((o) => o.followUpCount === 0)?.availableQuestionCount ?? 0
  if (totalAvailable < mainQuestionCount) {
    return `当前仅有 ${totalAvailable} 道启用题目，不足 ${mainQuestionCount} 道；请先生成或启用更多题目`
  }
  const best = Math.max(
    ...options.map((o) => (o.availableQuestionCount >= mainQuestionCount ? o.followUpCount : -1)),
  )
  return `按当前题量，每题最多可严格保证 ${best} 个追问（需要 ${mainQuestionCount} 道主问题）`
}
