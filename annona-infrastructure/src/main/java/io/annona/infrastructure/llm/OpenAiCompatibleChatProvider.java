package io.annona.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.model.ChatMessage;
import io.annona.common.model.ChatStreamListener;
import io.annona.common.model.StreamingChatProvider;
import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.dto.ModelResponse;
import io.annona.spi.dto.UsageInfo;
import io.annona.spi.model.ModelProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI 兼容 {@code POST /chat/completions} 实现，一个类同时落同步（spi 的
 * {@code ModelProvider}）与流式（common 的 {@code StreamingChatProvider}）两个端口，
 * 共用 HttpClient、超时、错误映射与 base-url 处理（qa-streaming-adr §决策 2）。
 * HTTP 用 JDK HttpClient（infrastructure 无 web 栈）；失败一律包装 BusinessException。
 *
 * <p>失败口径（错误码按需续号，不预留）：同步——连接层失败 1100、上游返回异常 1102；
 * 流式——建连/上游拒/协议坏 1104、流中途断（含缺 [DONE]）1105。重试不做（与 embedding
 * 同口径：调用方有兜底语义，网络层重试反而放大延迟）。流式的 {@code timeout-seconds}
 * 只约束到响应头，逐 token 无超时——SSE 端的服务端超时兜底断开，读线程存活到上游关流
 * 是已知残留（P1a 单用户接受；出现挂死实例再上读空闲超时）。
 */
public class OpenAiCompatibleChatProvider implements ModelProvider, StreamingChatProvider {

    private final ChatProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleChatProvider(ChatProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String name() {
        return properties.getModel().isBlank() ? "openai-compatible" : properties.getModel();
    }

    // ========== 同步（spi 契约：失败一律包装 BusinessException，禁止裸抛） ==========

    @Override
    public ModelResponse chat(List<ModelChatMessage> messages, ModelOptions options) {
        ensureConfigured();
        ModelOptions safeOptions = options == null ? ModelOptions.defaults() : options;
        String body = chatBody(messages.stream()
            .map(m -> new String[] {m.role(), m.content()})
            .toList(), false, safeOptions);
        HttpRequest request = buildRequest(body);
        try {
            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "chat 服务返回 HTTP " + response.statusCode());
            }
            return parseSyncResponse(response.body());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "chat 服务网络失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "chat 调用被中断");
        }
    }

    private ModelResponse parseSyncResponse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "chat 响应缺少 choices");
            }
            JsonNode message = choices.get(0).path("message");
            JsonNode usage = root.path("usage");
            UsageInfo usageInfo = usage.isMissingNode() ? null : new UsageInfo(
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
            String model = root.path("model").asText(properties.getModel());
            return new ModelResponse(message.path("content").asText(""), usageInfo, model);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "chat 响应解析失败");
        }
    }

    // ========== 流式（common 契约：恰好一个终态回调，之后无任何回调） ==========

    @Override
    public void streamChat(List<ChatMessage> messages, ChatStreamListener listener) {
        // 流式契约要求以 onError 终止而非抛出——配置错误也走回调（同步路径仍按 spi 契约抛出）
        try {
            ensureConfigured();
        } catch (BusinessException e) {
            listener.onError(e);
            return;
        }
        String body = chatBody(messages.stream()
            .map(m -> new String[] {m.role(), m.content()})
            .toList(), true, null);
        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(buildRequest(body), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            listener.onError(new BusinessException(ErrorCode.AI_STREAM_FAILED, "chat 服务连接失败"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(new BusinessException(ErrorCode.AI_STREAM_FAILED, "chat 调用被中断"));
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            listener.onError(new BusinessException(ErrorCode.AI_STREAM_FAILED,
                "chat 服务返回 HTTP " + response.statusCode() + " " + drainSnippet(response.body())));
            return;
        }

        StringBuilder full = new StringBuilder();
        OpenAiSseDecoder decoder = new OpenAiSseDecoder();
        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                for (String payload : decoder.feed(iterator.next())) {
                    if (handlePayload(payload, full, listener)) {
                        return;
                    }
                }
            }
            String tail = decoder.flush();
            if (tail != null && handlePayload(tail, full, listener)) {
                return;
            }
        } catch (UncheckedIOException e) {
            listener.onError(new BusinessException(ErrorCode.AI_STREAM_INTERRUPTED, "chat 上游中途断流"));
            return;
        }
        // 流关完也没见到 [DONE]：兼容协议里这是异常关闭，不当成完整回答
        listener.onError(new BusinessException(ErrorCode.AI_STREAM_INTERRUPTED, "chat 上游未发送 [DONE] 即结束"));
    }

    /** @return true = 已到终态（[DONE]，正常完成）。 */
    private boolean handlePayload(String payload, StringBuilder full, ChatStreamListener listener) {
        if ("[DONE]".equals(payload)) {
            listener.onComplete(full.toString());
            return true;
        }
        String delta = parseDelta(payload);
        if (delta != null && !delta.isEmpty()) {
            full.append(delta);
            listener.onDelta(delta);
        }
        return false;
    }

    /** @return 增量正文；role 帧 / usage 帧 / 空 choices 返回 {@code null}（跳过）。 */
    private String parseDelta(String payload) {
        try {
            JsonNode choices = mapper.readTree(payload).path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            return choices.get(0).path("delta").path("content").asText(null);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AI_STREAM_FAILED, "chat 流式响应解析失败");
        }
    }

    // ========== 共用 ==========

    private HttpRequest buildRequest(String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(stripTrailingSlash(properties.getBaseUrl()) + "/chat/completions"))
            .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!properties.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getApiKey());
        }
        return builder.build();
    }

    /**
     * 请求体。{@code options} 只在同步路径生效（流式一期无逐请求调参需求，加了没人消费）；
     * temperature / max_tokens 为 {@code null} 时省略字段，走上游默认。
     */
    private String chatBody(List<String[]> messages, boolean stream, ModelOptions options) {
        try {
            String model = options != null && options.model() != null
                ? options.model() : properties.getModel();
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages.stream()
                .map(m -> Map.of("role", m[0], "content", m[1]))
                .toList());
            body.put("stream", stream);
            if (options != null) {
                if (options.temperature() != null) {
                    body.put("temperature", options.temperature());
                }
                if (options.maxTokens() != null) {
                    body.put("max_tokens", options.maxTokens());
                }
            }
            return mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AI_STREAM_FAILED, "chat 请求体编码失败");
        }
    }

    private String drainSnippet(Stream<String> body) {
        try {
            String text = body.collect(Collectors.joining("\n"));
            return text.length() > 200 ? text.substring(0, 200) : text;
        } catch (UncheckedIOException e) {
            return "(错误响应体读取失败)";
        }
    }

    private void ensureConfigured() {
        if (properties.getBaseUrl().isBlank() || properties.getModel().isBlank()) {
            throw new BusinessException(ErrorCode.AI_STREAM_FAILED,
                "annona.model.chat 的 base-url / model 未配置完整");
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
