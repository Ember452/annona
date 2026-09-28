package io.annona.spi.dto;

/**
 * 检索命中的单个块。
 *
 * <p>本类型不含正文与偏移：引用跳转需要 {@code content}/{@code heading_path}/{@code char_start}，
 * 但把它们放进契约 jar 会让每个后端实现方都得返同一大块文本。消费方（如 qa）按
 * {@code chunkId} 批量回查 {@code kb_doc_chunk} 即可，一次 {@code IN} 查询拿全。
 *
 * @param docId   源文档 ID（对应 {@code kb_doc.id}）
 * @param chunkId 分块 ID（对应 {@code kb_doc_chunk.id}）
 * @param snippet 命中片段正文（可能截断）
 * @param score   相关度分数，已归一化到 [0,1]，越大越相关。pgvector 后端的口径是
 *                <b>RRF 融合分除以理论上限</b> {@code 2/(K+1)}（K=60），因此
 *                <b>双通道均排第一 ≈ 1.0，仅单通道命中最高 ≈ 0.5</b>——0.5 不是"低置信"，
 *                是"只被一路检索到"。展示引用置信度时按此解读，不要当百分比
 */
public record RetrievalHit(String docId, String chunkId, String snippet, double score) {
}
