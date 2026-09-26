package io.annona.modules.study.dto;

import java.time.Instant;

/**
 * POST /api/study/sessions/manual 请求体：手动补录一段学习时长。
 *
 * <p>时间取用户给的值（无心跳可验证），服务端直接判 SELF_REPORTED（ADR §决策 2）——
 * 该入口没有欺诈面，因为它本就声明"未经验证"。
 *
 * @param directionId 方向 id
 * @param startAt     补录起点
 * @param endAt       补录终点（跨度 >0 且 ≤24h）
 */
public record ManualSessionRequest(
    String directionId,
    Instant startAt,
    Instant endAt
) {
}
