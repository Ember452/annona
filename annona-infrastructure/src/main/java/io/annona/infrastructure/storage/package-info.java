/**
 * S3 兼容对象存储实现（knowledge-ingestion-adr §决策 8）：AWS SDK v2 + forcePathStyle，
 * 对接 PGSTY Silo（s3-storage-silo-adr）。key 布局与孤儿补偿的调用方语义见
 * {@link io.annona.common.storage.ObjectStorage}。
 */
package io.annona.infrastructure.storage;
