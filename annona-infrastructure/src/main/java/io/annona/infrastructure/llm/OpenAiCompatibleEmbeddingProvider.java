package io.annona.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.spi.dto.EmbeddingResult;
import io.annona.spi.dto.UsageInfo;
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
import java.util.TreeMap;

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
    public EmbeddingResult embed(List<String> texts) {
        ensureConfigured();
        if (texts.isEmpty()) {
            return new EmbeddingResult(List.of(), new UsageInfo(0, 0));
        }
        List<float[]> out = new ArrayList<>(texts.size());
        int promptTokens = 0;
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            BatchOutcome outcome = embedBatch(batch);
            out.addAll(outcome.vectors());
            // 跨批累加：供应商按批报账，一次 embed 调用的真账是各批之和
            promptTokens += outcome.usage().promptTokens();
        }
        return new EmbeddingResult(out, new UsageInfo(promptTokens, 0));
    }

    /** 单批结果（向量 + 该批 usage）；内部结构，不出端口。 */
    private record BatchOutcome(List<float[]> vectors, UsageInfo usage) {
    }

    private BatchOutcome embedBatch(List<String> batch) {
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
            return parseEmbeddings(response.body(), batch.size());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED, "embedding 服务网络失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED, "embedding 调用被中断");
        }
    }

    /**
     * 响应形状：{@code {"data":[{"index":0,"embedding":[…]}],"usage":{…}}}。协议<b>不保证</b>按请求
     * 顺序返回——以 index 排序对齐并校验条数，供应商乱序/丢项时显式报错而不是让向量与
     * chunk 静默错位（那会无声劣化检索质量，P1a-09 评测最不该背的锅）。
     * usage 缺失时填 0（宁缺毋假账，llmprovider-metering-adr 否决表）。
     */
    private BatchOutcome parseEmbeddings(String body, int expectedCount) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != expectedCount) {
                throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED,
                    "embedding 返回 " + data.size() + " 条，与请求的 " + expectedCount + " 条不一致");
            }
            TreeMap<Integer, float[]> byIndex = new TreeMap<>();
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
                byIndex.put(item.path("index").asInt(), values);
            }
            if (byIndex.firstKey() != 0 || byIndex.lastKey() != expectedCount - 1) {
                throw new BusinessException(ErrorCode.KB_EMBEDDING_FAILED,
                    "embedding 返回的 index 集合不是 0.." + (expectedCount - 1));
            }
            JsonNode usage = root.path("usage");
            UsageInfo usageInfo = new UsageInfo(usage.path("prompt_tokens").asInt(0), 0);
            return new BatchOutcome(new ArrayList<>(byIndex.values()), usageInfo);
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
