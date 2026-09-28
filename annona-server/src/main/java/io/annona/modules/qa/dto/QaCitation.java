package io.annona.modules.qa.dto;

/**
 * 结构化引用（V6 citations JSONB 的元素形状；qa-streaming-adr §决策 4）。
 * 上游（🅖/🅢）没有结构化引用，本形状自研；前端据 {@code docId}+{@code chunkId}
 * 跳转分块预览（P1a-08 验收"引用可点击跳回原文段落"）。
 *
 * @param docId       源文档 id
 * @param chunkId     分块 id（对应 kb_doc_chunk.id）
 * @param chunkIndex  分块序号（同文档内消歧）
 * @param headingPath 标题路径（"章 > 节"），无标题为空串
 * @param snippet     分块正文前 300 字符——完整正文回查 kb_doc_chunk，留痕与面板够用
 * @param score       检索融合分（[0,1]，批 2 口径）
 */
public record QaCitation(String docId, String chunkId, int chunkIndex,
                         String headingPath, String snippet, double score) {
}
