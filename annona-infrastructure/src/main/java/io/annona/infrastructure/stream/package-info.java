/**
 * Redisson 任务流实现（knowledge-ingestion-adr §决策 8）：消费组读取 + pending 认领 +
 * 业务侧重试计数的最小封装，语义借 🅖 AbstractStreamProducer/AbstractStreamConsumer。
 */
package io.annona.infrastructure.stream;
