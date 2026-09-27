package io.annona.modules.knowledge.chunk;

/**
 * 分块器的单个输出分块。落库对应 {@code kb_doc_chunk} 行（P1a-05），
 * 检索命中对应 SPI {@code RetrievalHit.chunkId}。
 *
 * @param index      全文档内连续序号（0..n-1），落库为 chunk_index
 * @param headingPath 所在节的标题路径（"章 > 节 > 小节"）；文档无标题时为空串
 * @param charStart  正文在清洗后全文中的起始偏移（含）；不含生成的前缀，引用跳转依据
 * @param charEnd    正文结束偏移（不含）
 * @param text       分块全文 = 标题路径前缀行 + "\n" + 节正文（前缀供 embedding 上下文，
 *                   偏移只覆盖正文部分——这一不对称是刻意的，见 Chunker 类注释）
 */
public record KnowledgeChunk(int index, String headingPath, int charStart, int charEnd, String text) {
}
