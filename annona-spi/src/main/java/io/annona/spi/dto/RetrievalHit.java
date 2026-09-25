package io.annona.spi.dto;

/**
 * 检索命中的单个块。
 *
 * @param docId   源文档 ID（对应 {@code kb_doc.id}）
 * @param chunkId 分块 ID（对应 {@code kb_doc_chunk.id}）
 * @param snippet 命中片段正文（可能截断）
 * @param score   相关度分数；已归一化到 [0,1]，越大越相关
 */
public record RetrievalHit(String docId, String chunkId, String snippet, double score) {
}
