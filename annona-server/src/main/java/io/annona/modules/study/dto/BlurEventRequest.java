package io.annona.modules.study.dto;

/**
 * POST /api/study/sessions/{id}/events 请求体。
 *
 * @param type 仅接受 BLUR（前端失焦上报）；其余事件类型由服务端在状态转换时自落，
 *             前端上报非 BLUR 一律 1001。
 */
public record BlurEventRequest(String type) {
}
