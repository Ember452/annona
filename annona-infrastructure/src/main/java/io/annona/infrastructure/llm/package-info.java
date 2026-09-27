/**
 * 模型网关的 embedding 通道（knowledge-ingestion-adr §决策 5）：OpenAI 兼容
 * {@code /embeddings} 协议实现 {@link io.annona.spi.model.EmbeddingProvider}。
 * Key 走 env 注入（平台代持），BYOK（用户 Key 入库路由）是 P1b-10，本包不假设
 * Key 只来自 env——provider 解析层扩而不会改接口。
 */
package io.annona.infrastructure.llm;
