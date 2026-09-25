package io.annona.spi.dto;

/**
 * 检索请求。P0-04 只落最小字段集，向量维度、filter DSL 等在 P1a-07 实现时扩展。
 *
 * @param text     查询文本（改写后的最终形态）
 * @param topK     期望返回的命中数上限
 * @param userId   发起方；用于按用户隔离知识库可见性
 * @param kbDocIds 可选：将检索限定在指定文档集合（{@code null} 或空表示全域）
 */
public record RetrievalQuery(String text, int topK, String userId, java.util.List<String> kbDocIds) {
}
