package io.annona.modules.study.dto;

/**
 * POST /api/study/sessions 请求体：开始番茄钟会话。
 *
 * @param directionId    方向 id（必填，写前经 DirectionQueryService 校验可见性）
 * @param plannedMinutes 计划专注分钟数（1..240，仅用于前端倒计时节奏，不落库——
 *                       时长以服务端心跳判定为准）
 */
public record StartSessionRequest(
    String directionId,
    Integer plannedMinutes
) {
}
