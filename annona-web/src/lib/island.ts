/**
 * 学习小岛数据映射（P2-02）：打卡数 → 解锁地块数、方向 → 植物形态、布局 → 3D/2D 决策。
 *
 * 纯函数层（沿 lib/statsView 先例）：Canvas/WebGL 内部不进 jsdom 测试，所有可证伪的
 * 口径都收在这里。生长机制的口径决策：
 *  - 解锁数 = 初始 150 + 全部打卡数 × 5（借 🅢 learning-island 的留存语义：每天打开
 *    就有新格子长出来）；连续天数/累计时长/方向覆盖度进文案与 aria（P2-02 任务行的
 *    「驱动生长」以解锁节奏体现，不另造第二套生长公式——一个可解释的机制好过两个对不上的）。
 *  - 植物形态按方向哈希分种：不同方向的格子长不同姿态的树/蕨（方向覆盖度可见）。
 */

/** 地块常量（与上游一致；改动需与 LearningIsland 的网格生成同步）。 */
export const ISLAND_INITIAL_TILES = 150
export const ISLAND_TILES_PER_CHECKIN = 5

/** Mulberry32 种子化随机（小岛解锁洗牌用，与上游同款实现）。 */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 稳定字符串哈希（×31 循环）：方向 id → 植物形态的确定性来源。 */
export function stableHash(input: string): number {
  let hash = 0
  for (let i = 0; i < input.length; i++) {
    hash = (hash * 31 + input.charCodeAt(i)) | 0
  }
  return Math.abs(hash)
}

/** 累计打卡数 → 已解锁地块数（封顶在总地块数）。 */
export function unlockedTileCount(totalCheckins: number, totalTiles: number): number {
  return Math.min(totalTiles, ISLAND_INITIAL_TILES + totalCheckins * ISLAND_TILES_PER_CHECKIN)
}

/**
 * 解锁序号 → 植物形态编号。
 * 无方向（新用户）退化为按序号轮换；有方向时按「解锁序号 % 方向数」归属方向，再由
 * 方向 id 的稳定哈希给形态——同一方向永远长同一种姿态，不同方向肉眼可分。
 */
export function plantVariantForTile(unlockOrder: number, directionIds: string[]): number {
  if (directionIds.length === 0) {
    return unlockOrder % 3
  }
  const owner = directionIds[(unlockOrder - 1) % directionIds.length]
  return stableHash(owner) % 6
}

/** 小岛无障碍文案（3D 与 2D 降级共用一份口径）。 */
export function islandAriaLabel(stats: {
  totalCheckins: number
  streak: number
  totalMinutes: number
  directionCount: number
}): string {
  const hours = Math.round(stats.totalMinutes / 6) / 10
  return `学习小岛：累计 ${stats.totalCheckins} 天打卡，连续 ${stats.streak} 天，`
    + `共专注 ${hours} 小时，覆盖 ${stats.directionCount} 个方向`
}

export interface IslandLayoutInput {
  coarsePointer: boolean
  viewportWidth: number
}

/** 3D/2D 降级决策：触屏主设备或窄视口走 2D（帧率预算，30fps 保底不达标就别上 WebGL）。 */
export function shouldUseIsland3D(input: IslandLayoutInput): boolean {
  return !input.coarsePointer && input.viewportWidth >= 768
}
