package io.annona.infrastructure.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * embedding 通道配置（{@code annona.model.embedding.*}）。provider=none（默认）时
 * 不装配实现，向量化时报 KB_EMBEDDING_NOT_CONFIGURED；openai-compatible 时装配
 * OpenAI 兼容实现（DashScope 兼容模式同协议）。
 *
 * <p>取值口径：dimensions=1024 必须与 V4 DDL {@code vector(1024)} 一致，改它 = 新迁移
 * （ADR §后果）；batchSize=10 借 🅖（DashScope 单批硬上限，OpenAI 兼容协议通用安全值）；
 * Key 为空时首调用即报错——启动不 fail-fast，错误后移到使用点（ADR §决策 9）。
 */
@ConfigurationProperties(prefix = "annona.model.embedding")
public class EmbeddingProperties {

    /** none = 关闭向量化能力；openai-compatible = OpenAI 兼容 /embeddings 协议。 */
    private String provider;

    private String baseUrl;

    private String apiKey;

    /** 模型 id；同时是 {@code kb_doc.embedding_model} 的落库口径（检索过滤依据）。 */
    private String model;

    /** 产出向量维度；必须与向量列 DDL 一致。 */
    private int dimensions;

    /** 单批嵌入文本数（供应商上限约束）。 */
    private int batchSize;

    /** 单次 HTTP 调用超时（秒）。 */
    private int timeoutSeconds;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
