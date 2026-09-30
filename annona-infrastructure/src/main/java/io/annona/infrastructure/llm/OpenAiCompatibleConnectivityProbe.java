package io.annona.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.llm.LlmConnectivityProbe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JDK HttpClient 的 OpenAI 兼容探测：一次 1-token chat 请求（借 🅖 test 端点语义）。
 * 独立于 ChatProperties 全局配置——探测的就是"这套候选值本身"，走全局 provider
 * 就成了自证。超时 10s：连通性判断不需要等生成完，等首响应即够。
 *
 * <p>日志只记状态码与异常类型，绝不记请求体/响应体（含 Key）。
 */
@Component
public class OpenAiCompatibleConnectivityProbe implements LlmConnectivityProbe {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleConnectivityProbe.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ProbeResult probe(String baseUrl, String apiKey, String model) {
        try {
            String root = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
            String body = objectMapper.writeValueAsString(Map.of(
                "model", model == null ? "" : model,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 1));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(root.replaceAll("/+$", "") + "/chat/completions"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new ProbeResult(true, "连接成功");
            }
            if (status == 401 || status == 403) {
                return new ProbeResult(false, "鉴权失败：请检查 API Key 是否有效");
            }
            if (status == 404) {
                return new ProbeResult(false, "模型或地址不存在：请检查 model 与 Base URL");
            }
            log.warn("连通性探测上游异常状态：{}", status);
            return new ProbeResult(false, "上游返回 " + status + "，请稍后重试或检查配置");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "探测被中断，请重试");
        } catch (Exception e) {
            log.warn("连通性探测失败：{}", e.getClass().getSimpleName());
            return new ProbeResult(false, "无法建立连接：请检查 Base URL 与网络");
        }
    }
}
