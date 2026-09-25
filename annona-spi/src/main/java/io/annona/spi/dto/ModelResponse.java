package io.annona.spi.dto;

/**
 * 模型响应。{@code content} 为文本正文；结构化输出走 Spring AI 侧，不进入本契约。
 *
 * @param content 响应正文（可能为空字符串，如被安全策略拦截）
 * @param usage   token 用量；provider 未返回时为 {@code null}
 * @param model   实际使用的模型名；用于评估可比性留痕
 */
public record ModelResponse(String content, UsageInfo usage, String model) {
}
