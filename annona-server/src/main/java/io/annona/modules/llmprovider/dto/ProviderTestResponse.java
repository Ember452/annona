package io.annona.modules.llmprovider.dto;

/**
 * 连通性测试结论（成功才走到响应体；失败经 2902 的 Result.error 文案）。
 *
 * @param ok      恒 true（防御性保留字段：未来支持"带错误详情的部分成功"时不改形状）
 * @param message 结论文案
 */
public record ProviderTestResponse(boolean ok, String message) {
}
