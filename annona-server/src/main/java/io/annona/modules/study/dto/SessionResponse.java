package io.annona.modules.study.dto;

import java.time.Instant;

/**
 * 学习会话响应（列表与 start/finish 返回共用形状）。
 *
 * @param id          会话 id（后续心跳/事件/finish 的路径参数）
 * @param directionId 方向 id
 * @param mode        POMODORO | IMMERSIVE | CHECKIN
 * @param startAt     开始时刻
 * @param endAt       结束时刻；null = 进行中（前端本地状态显示）
 * @param minutes     服务端判定时长；进行中为 null
 * @param quality     VERIFIED | PARTIAL | SELF_REPORTED；进行中为 null——
 *                    面板必须显示质量等级（设计 §6.1）
 */
public record SessionResponse(
    String id,
    String directionId,
    String mode,
    Instant startAt,
    Instant endAt,
    Integer minutes,
    String quality
) {
}
