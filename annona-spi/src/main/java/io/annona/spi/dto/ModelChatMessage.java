package io.annona.spi.dto;

/**
 * 模型对话消息。role 由业务侧决定（{@code system / user / assistant / tool}），SPI 层不校验取值。
 *
 * @param role    消息角色
 * @param content 消息正文（UTF-8 文本；多模态资源引用属扩展点外，走独立 metadata）
 */
public record ModelChatMessage(String role, String content) {
}
