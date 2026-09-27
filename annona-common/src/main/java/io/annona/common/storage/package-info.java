/**
 * 对象存储端口（knowledge-ingestion-adr §决策 8）：S3 兼容协议的最小接口。
 *
 * <p>实现（AWS SDK v2 + forcePathStyle，对接 PGSTY Silo）在 annona-infrastructure
 * 的 storage 包；业务模块只注入本端口。未配置 endpoint 时实现不装配（上传时报
 * KB_DOC_STORAGE_NOT_CONFIGURED），启动不 fail-fast（ADR §决策 9）。
 */
package io.annona.common.storage;
