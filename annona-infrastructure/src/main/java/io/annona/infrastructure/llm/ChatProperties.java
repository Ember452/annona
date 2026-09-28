package io.annona.infrastructure.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * chat 通道配置（{@code annona.model.chat.*}，qa-streaming-adr）。provider=none（默认）
 * 时不装配实现，提问报 QA_MODEL_NOT_CONFIGURED（2502）；openai-compatible 时装配
 * OpenAI 兼容实现（同步 + 流式同一实现）；fake 时装配确定性流式 fake。
 *
 * <p>默认值唯一出处是 {@code application.yaml} 的 {@code ${ENV:default}}，本类不留字段
 * 初值（PropertiesDefaultSourceTest 门禁）。错误后移到使用点，启动不 fail-fast（与
 * embedding 同口径，knowledge-ingestion-adr §决策 9）。
 */
@ConfigurationProperties(prefix = "annona.model.chat")
public class ChatProperties {

    /** none = 关闭 chat 能力；openai-compatible = OpenAI 兼容 /chat/completions；fake = 确定性流式。 */
    private String provider;

    /** OpenAI 兼容端点根（如 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}）。 */
    private String baseUrl;

    /** API Key；可空——本地网关（Ollama/LM Studio）常不设鉴权，空时不发 Authorization 头。 */
    private String apiKey;

    /** 模型 id；同时是用量归属的 {@code name()} 口径。 */
    private String model;

    /** 单次 HTTP 调用超时（秒）；对流式只约束到响应头，逐 token 无超时（实现类注释的已知残留）。 */
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

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
