package io.annona.modules.study.dto;

/**
 * POST /api/study/sessions/{id}/finish 请求体。
 *
 * @param abandon true = 中途放弃（落 INTERRUPT 事件）；false/缺省 = 正常完成（FINISH）。
 *                时长与质量一律由服务端按心跳时间线判定，前端无可传字段。
 */
public record FinishSessionRequest(boolean abandon) {
}
