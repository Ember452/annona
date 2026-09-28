/**
 * 模型网关实现（OpenAI 兼容协议）：embedding 通道实现 spi 的 {@code EmbeddingProvider}
 * （knowledge-ingestion-adr §决策 5）；chat 通道一个类同时实现 spi 的 {@code ModelProvider}
 * （同步）与 common 的 {@code StreamingChatProvider}（流式，qa-streaming-adr）。
 * Key 走 env 注入（平台代持），BYOK（用户 Key 入库路由）是 P1b-10，本包不假设
 * Key 只来自 env——provider 解析层扩而不会改接口。
 */
package io.annona.infrastructure.llm;
