package io.annona.common.model;

/**
 * 流式 chat 的对话消息，与 spi 的 {@code ModelChatMessage} 同形（common 不得依赖
 * spi，见包注释）。
 *
 * @param role    消息角色（system / user / assistant）；取值校验留给 Provider
 * @param content 消息正文（UTF-8 文本）
 */
public record ChatMessage(String role, String content) {
}
