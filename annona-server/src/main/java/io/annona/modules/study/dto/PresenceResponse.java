package io.annona.modules.study.dto;

/**
 * 匿名共学状态响应（P2-05）。
 *
 * @param online 当前在场人数（滑窗内有过轮询的用户数）——只有计数，无任何身份信息
 */
public record PresenceResponse(long online) {
}
