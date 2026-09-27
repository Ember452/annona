/**
 * 任务流端口（knowledge-ingestion-adr §决策 8）：Redis Stream 的最小封装——
 * 消息瘦身投递 + 消费组 ACK + pending 认领，语义借 🅖 AbstractStreamProducer/Consumer。
 *
 * <p>实现（Redisson）在 annona-infrastructure 的 stream 包；业务模块（knowledge
 * 入库队列）只注入本端口。overview §5 的规划落点（"文档向量化走 Redis Stream"）。
 */
package io.annona.common.stream;
