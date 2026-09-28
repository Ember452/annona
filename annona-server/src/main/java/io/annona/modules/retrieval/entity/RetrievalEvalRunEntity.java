package io.annona.modules.retrieval.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * retrieval_eval_run 一次评测运行的指标投影（V5 §4；P1a-09）。
 *
 * <p>真相源是 git 里的 JSON 报告与 {@code docs/benchmarks/}，本表让"混合 vs 纯向量"的
 * 头部数字可跨轮查询——与 {@code user_session} 的审计投影同定位。
 *
 * <p>{@code created_at} 与 {@code buckets_json} 两列<b>刻意不映射</b>：前者由 DB 默认值
 * 兜底，后者的分桶数字只活在报告里（表里再存一份就是两处真相）；未映射列被 Hibernate
 * validate 忽略（direction.meta_json 先例）。仓内没有 jsonb 映射先例，不为投影表开第一个。
 *
 * <p>id 由应用侧赋值（全仓约定），因此 {@code save()} 走 merge 分支——返回值才是受管副本。
 * 本表只插入不回读 DB 默认列，所以不需要 refresh（切记不要拿入参实体去映射响应）。
 */
@Entity
@Table(name = "retrieval_eval_run")
public class RetrievalEvalRunEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 查询集标识（通常是 queries.json 的路径或版本号）。 */
    @Column(name = "query_set", nullable = false, length = 128)
    private String querySet;

    /** 本轮人读标签（如 "baseline-jieba-tsvector"），报告与表靠它对齐。 */
    @Column(name = "label", nullable = false, length = 128)
    private String label;

    /** BOTH / SEMANTIC / KEYWORD，与 RetrievalMode 同名取值。 */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "backend", nullable = false, length = 32)
    private String backend;

    /** openai-compatible 还是 fake；**必填**，否则 fake 的管道回归会被当成质量结论。 */
    @Column(name = "embedding_provider", nullable = false, length = 32)
    private String embeddingProvider;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "top_k", nullable = false)
    private int topK;

    @Column(name = "query_count", nullable = false)
    private int queryCount;

    @Column(name = "recall_at_k", precision = 5, scale = 4)
    private BigDecimal recallAtK;

    @Column(name = "mrr_at_k", precision = 5, scale = 4)
    private BigDecimal mrrAtK;

    @Column(name = "latency_p50_ms")
    private Integer latencyP50Ms;

    @Column(name = "latency_p95_ms")
    private Integer latencyP95Ms;

    /** 报告文件路径或 artifact 链接（本轮数字的出处）。 */
    @Column(name = "report_path", length = 500)
    private String reportPath;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getQuerySet() {
        return querySet;
    }

    public void setQuerySet(String querySet) {
        this.querySet = querySet;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getQueryCount() {
        return queryCount;
    }

    public void setQueryCount(int queryCount) {
        this.queryCount = queryCount;
    }

    public BigDecimal getRecallAtK() {
        return recallAtK;
    }

    public void setRecallAtK(BigDecimal recallAtK) {
        this.recallAtK = recallAtK;
    }

    public BigDecimal getMrrAtK() {
        return mrrAtK;
    }

    public void setMrrAtK(BigDecimal mrrAtK) {
        this.mrrAtK = mrrAtK;
    }

    public Integer getLatencyP50Ms() {
        return latencyP50Ms;
    }

    public void setLatencyP50Ms(Integer latencyP50Ms) {
        this.latencyP50Ms = latencyP50Ms;
    }

    public Integer getLatencyP95Ms() {
        return latencyP95Ms;
    }

    public void setLatencyP95Ms(Integer latencyP95Ms) {
        this.latencyP95Ms = latencyP95Ms;
    }

    public String getReportPath() {
        return reportPath;
    }

    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
    }
}
