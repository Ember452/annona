package io.annona.modules.knowledge.dto;

/**
 * 分块跨模块只读视图（qa 的引用组装消费；与 KbDocChunkView 的区别是带 doc/chunk id、
 * 不带源文偏移——引用跳转由前端持 chunkId 调分块预览接口，无需偏移）。
 *
 * @param docId       源文档 id
 * @param chunkId     分块 id
 * @param index       分块序号
 * @param headingPath 标题路径（"章 > 节"），无标题为空串
 * @param content     分块正文（含标题路径前缀行，与 embedding 输入一致）
 */
public record KbChunkReference(String docId, String chunkId, int index,
                               String headingPath, String content) {
}
