package io.annona.modules.retrieval.dto;

/**
 * 评测运行写入请求（{@code POST /api/retrieval/eval-run}，P1a-09）。
 *
 * <p>只给 {@code scripts/rag-eval} 用，不是通用查询接口：字段与 JSON 报告一一对应，
 * 表是报告的投影。<b>{@code embeddingProvider} 必填</b>——fake 与真模型的两轮数字落在
 * 同一张表里却不可比较（检索按 kb_doc.embedding_model 过滤，fake 语料的召回没有语义含义）。
 *
 * @param querySet         查询集标识（queries.json 路径或版本号）
 * @param label            本轮人读标签
 * @param mode             BOTH / SEMANTIC / KEYWORD
 * @param backend          本轮检索后端（pgvector / fake）
 * @param embeddingProvider 向量来源（openai-compatible / fake）
 * @param embeddingModel   具体模型 id，可空（fake 时无意义）
 * @param topK             计算 Recall@K/MRR@K 用的 K
 * @param queryCount       参与打分的查询条数
 * @param recallAtK        Recall@K，[0,1]；无法计算时 null（如全部查询都没有标注）
 * @param mrrAtK           MRR@K，[0,1]
 * @param latencyP50Ms     端到端 P50
 * @param latencyP95Ms     端到端 P95
 * @param reportPath       报告文件路径或 artifact 链接
 */
public record EvalRunRequest(String querySet, String label, String mode, String backend,
                             String embeddingProvider, String embeddingModel, Integer topK,
                             Integer queryCount, Double recallAtK, Double mrrAtK,
                             Integer latencyP50Ms, Integer latencyP95Ms, String reportPath) {
}
