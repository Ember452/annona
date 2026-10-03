package io.annona.modules.study.dto;

/**
 * POST /api/study/sessions 请求体：开始专注会话。
 *
 * @param directionId    方向 id（必填，写前经 DirectionQueryService 校验可见性）
 * @param plannedMinutes 计划专注分钟数（1..240，仅用于前端倒计时节奏，不落库——
 *                       时长以服务端心跳判定为准）
 * @param mode           POMODORO（缺省）| IMMERSIVE（P2-03 沉浸模式）；CHECKIN 不开放——
 *                       打卡联动会话只能由 CheckinService 在打卡事务内创建（ADR §决策 3）
 */
public record StartSessionRequest(
    String directionId,
    Integer plannedMinutes,
    String mode
) {

    /** 两参便利构造：番茄钟路径的既有调用点（P1a-04）缺省 mode=POMODORO。 */
    public StartSessionRequest(String directionId, Integer plannedMinutes) {
        this(directionId, plannedMinutes, null);
    }
}
