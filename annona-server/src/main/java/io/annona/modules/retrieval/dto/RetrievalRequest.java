package io.annona.modules.retrieval.dto;

/**
 * 检索请求体（{@code POST /api/retrieval/query}）。
 *
 * @param query 查询文本；空白直接报 2401，不允许"空查询扫全库"
 * @param topK  期望命中数上限；{@code null} 走默认值（在 service 层定，配置化推迟到 P1b）
 * @param mode  通道开关 {@code BOTH}/{@code SEMANTIC}/{@code KEYWORD}；{@code null} 归一为 BOTH。
 *              这是给 P1a-09 评测跑对照组用的，普通用户不传
 */
public record RetrievalRequest(String query, Integer topK, String mode) {
}
