/**
 * chat 模型端口：同步语义在 annona-spi 的 {@code ModelProvider}，流式语义在本包的
 * {@code StreamingChatProvider}（qa-streaming-adr：流式无外部实现方需求，属内部解耦
 * 端口，不进对外发布的 spi）。
 *
 * <p>common 不得依赖 spi，因此本包自带 {@code ChatMessage}——与 spi 的
 * {@code ModelChatMessage} 同形 (role, content)，由 infrastructure 实现类分别落点。
 */
package io.annona.common.model;
