package io.annona.modules.questionbank.model;

import java.util.UUID;

/**
 * 出题上下文快照（qb_question.sources JSONB 元素）：记录题目出自哪个文档/分块，供题目页
 * 溯源展示与后续 hit_rate 标定。无 FK 快照语义——文档删除后题目仍在（qa citations 同款）。
 */
public record QbSourceRef(UUID docId, UUID chunkId, String headingPath) {
}
