package io.annona.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.spi.model.EmbeddingProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 {@code POST /embeddings} 实现（DashScope 兼容模式同协议，借 🅖
 * LlmProviderRegistry 的"一个 OpenAI 兼容 Provider 服务多用途"思路；annona 不引入
 * Spring AI——batch/重试/错误语义自己握）。HTTP 用 JDK HttpClient（infrastructure
 * 无 web 栈；失败一律包装 BusinessException，SPI 契约）。
 *
 * <p>失败口径：重试不做（调用方状态机有恢复调度兜底，网络层重试反而放大延迟，
 * 🅖 同口径 spring.ai.retry.max-attempts=1）；批量 ≤ batchSize（DashScope 硬上限）。
 */
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleEmbeddingProvider(EmbeddingProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String name() {
        // 落库到 kb_doc.embedding_model 的就是模型 id；检索端按当前配置的同一模型过滤
        return properties.getModel().isBlank() ? "openai-compatible" : properties.getModel();
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        ensureConfigured();
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            out.addAll(embedBatch(batch));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> batch) {
        HttpRequest request;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", properties.getModel());
            body.put("input", batch);
            request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(properties.getBaseUrl()) + "/embeddings"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED, "请求体编码失败");
        }
        try {
            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED,
                    "embedding 服务返回 HTTP " + response.statusCode());
            }
            return parseEmbeddings(response.body());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED, "embedding 服务网络失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED, "embedding 调用被中断");
        }
    }

    /** 响应形状：{@code {"data":[{"index":0,"embedding":[…]}]}}；按 index 排序后取向量。 */
    private List<float[]> parseEmbeddings(String body) {
        try {
            JsonNode data = mapper.readTree(body).path("data");
            List<float[]> out = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode vector = item.path("embedding");
                float[] values = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    values[i] = (float) vector.get(i).asDouble();
                }
                if (values.length != properties.getDimensions()) {
                    throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED,
                        "embedding 维度 " + values.length + " 与配置 " + properties.getDimensions() + " 不一致");
                }
                out.add(values);
            }
            return out;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED, "embedding 响应解析失败");
        }
    }

    private void ensureConfigured() {
        if (properties.getBaseUrl().isBlank() || properties.getApiKey().isBlank()
            || properties.getModel().isBlank()) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_NOT_CONFIGURED,
                "annona.model.embedding 的 base-url / api-key / model 未配置完整");
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
