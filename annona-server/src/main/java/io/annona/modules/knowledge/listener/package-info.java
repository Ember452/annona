/**
 * 入库任务流监听（knowledge-ingestion-adr §决策 3/8）：Redis Stream 的投递与消费注册、
 * 恢复调度。机制借 🅖 Vectorize{StreamProducer,StreamConsumer,RecoveryScheduler}。
 */
package io.annona.modules.knowledge.listener;
