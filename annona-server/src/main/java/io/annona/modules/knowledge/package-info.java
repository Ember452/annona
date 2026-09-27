/**
 * knowledge 模块：知识库文档入库与检索数据落库——上传（S3 + 内容 hash 幂等）、
 * 解析调度、分块、向量化状态机与进度 SSE。
 *
 * <p>读侧检索在 modules/retrieval（P1a-07，与写侧分离，结构文档 §规划）；
 * 方向归属校验只经 shared.direction 的 DirectionQueryService。
 * 允许依赖：annona-common 端口（parse/storage/stream）、annona-spi（EmbeddingProvider）。
 * 禁止 import annona-infrastructure 与其他业务模块。
 * 表 / 状态机 / 端口归属口径：docs/specs/2026-09-27-knowledge-ingestion-adr.md。
 */
package io.annona.modules.knowledge;
