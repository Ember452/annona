package io.annona.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.annona.common.exception.BusinessException;
import io.annona.common.model.ChatMessage;
import io.annona.common.model.ChatStreamListener;
import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.dto.UsageInfo;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用 JDK 内置 HttpServer 起本地端点，对 streamChat/chat 的线上行为做真 HTTP 断言
 * （无三方依赖、本机可跑）。SSE 分帧细节归 {@link OpenAiSseDecoderTest}。
 */
@DisplayName("OpenAiCompatibleChatProvider：同步与流式 chat")
class OpenAiCompatibleChatProviderTest {

    private static final String SSE_BODY = """
        data: {"choices":[{"delta":{"role":"assistant"}}]}

        data: {"choices":[{"delta":{"content":"你"}}]}

        data: {"choices":[{"delta":{"content":"好"}}]}

        data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2}}

        data: [DONE]

        """;

    /** 同一正文但不回填 usage 帧（个别网关不支持 include_usage）：终态应携全 0。 */
    private static final String SSE_BODY_NO_USAGE = """
        data: {"choices":[{"delta":{"content":"你"}}]}

        data: {"choices":[{"delta":{"content":"好"}}]}

        data: [DONE]

        """;

    private HttpServer server;
    private OpenAiCompatibleChatProvider provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        provider = new OpenAiCompatibleChatProvider(properties(baseUrl()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    private static ChatProperties properties(String baseUrl) {
        ChatProperties properties = new ChatProperties();
        properties.setProvider("openai-compatible");
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("test-key");
        properties.setModel("qwen-test");
        properties.setTimeoutSeconds(5);
        return properties;
    }

    private void serve(byte[] body, int status, boolean truncate) {
        server.createContext("/v1/chat/completions", exchange -> {
            if (truncate) {
                // 只发一半就关流：客户端读到 EOF，等价上游中途断流
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body, 0, body.length / 2);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private RecordingListener stream() {
        RecordingListener listener = new RecordingListener();
        provider.streamChat(List.of(
            new ChatMessage("system", "你是助手"),
            new ChatMessage("user", "你好")), listener);
        return listener;
    }

    @Test
    @DisplayName("流式快乐路径：delta 按序回调，onComplete 收到全量拼接与 usage 终帧，终态后无回调")
    void streamHappyPath() {
        serve(SSE_BODY.getBytes(StandardCharsets.UTF_8), 200, false);

        RecordingListener listener = stream();

        assertThat(listener.deltas).containsExactly("你", "好");
        assertThat(listener.completed.get()).isEqualTo("你好");
        assertThat(listener.usage.get()).isNotNull();
        assertThat(listener.usage.get().promptTokens()).isEqualTo(7);
        assertThat(listener.usage.get().completionTokens()).isEqualTo(2);
        assertThat(listener.error.get()).isNull();
    }

    @Test
    @DisplayName("供应商未回填 usage：onComplete 携全 0（宁缺毋假账，不估算）")
    void streamWithoutUsageFrameCarriesZeroUsage() {
        serve(SSE_BODY_NO_USAGE.getBytes(StandardCharsets.UTF_8), 200, false);

        RecordingListener listener = stream();

        assertThat(listener.completed.get()).isEqualTo("你好");
        assertThat(listener.usage.get()).isNotNull();
        assertThat(listener.usage.get().totalTokens()).isZero();
    }

    @Test
    @DisplayName("非 2xx：onError 带 AI_STREAM_FAILED(1104)，无 delta 无完成")
    void non2xxMapsToStreamFailed() {
        serve("{\"error\":\"quota\"}".getBytes(StandardCharsets.UTF_8), 429, false);

        RecordingListener listener = stream();

        assertThat(listener.error.get()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) listener.error.get()).getCode()).isEqualTo(1104);
        assertThat(listener.deltas).isEmpty();
        assertThat(listener.completed.get()).isNull();
    }

    @Test
    @DisplayName("上游中途断流（缺 [DONE]）：onError 带 AI_STREAM_INTERRUPTED(1105)")
    void truncatedStreamMapsToInterrupted() {
        serve(SSE_BODY.getBytes(StandardCharsets.UTF_8), 200, true);

        RecordingListener listener = stream();

        assertThat(listener.error.get()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) listener.error.get()).getCode()).isEqualTo(1105);
        assertThat(listener.completed.get()).isNull();
    }

    @Test
    @DisplayName("base-url / model 缺配置：即抛 AI_STREAM_FAILED，不发请求")
    void unconfiguredFailsFast() {
        OpenAiCompatibleChatProvider unconfigured =
            new OpenAiCompatibleChatProvider(properties(""));

        RecordingListener listener = new RecordingListener();
        unconfigured.streamChat(List.of(new ChatMessage("user", "hi")), listener);

        assertThat(listener.error.get()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) listener.error.get()).getCode()).isEqualTo(1104);
    }

    @Test
    @DisplayName("同步 chat：解析正文与 usage，model 取响应值")
    void syncChatParsesResponse() {
        serve("""
            {"model":"qwen-test","choices":[{"message":{"role":"assistant","content":"同步回答"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}
            """.getBytes(StandardCharsets.UTF_8), 200, false);

        var response = provider.chat(List.of(new ModelChatMessage("user", "hi")), ModelOptions.defaults());

        assertThat(response.content()).isEqualTo("同步回答");
        assertThat(response.usage().promptTokens()).isEqualTo(10);
        assertThat(response.usage().completionTokens()).isEqualTo(5);
        assertThat(response.model()).isEqualTo("qwen-test");
    }

    @Test
    @DisplayName("name() 返回模型 id（用量归属口径）")
    void nameReturnsModelId() {
        assertThat(provider.name()).isEqualTo("qwen-test");
    }

    /** 回调收集器：终态后若再有回调即失败（契约：终态之后不得有任何回调）。 */
    private static final class RecordingListener implements ChatStreamListener {

        private final List<String> deltas = new ArrayList<>();
        private final AtomicReference<String> completed = new AtomicReference<>();
        private final AtomicReference<UsageInfo> usage = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private boolean terminal;

        @Override
        public void onDelta(String delta) {
            if (terminal) {
                throw new IllegalStateException("终态后出现 onDelta");
            }
            deltas.add(delta);
        }

        @Override
        public void onComplete(String fullText, UsageInfo completionUsage) {
            if (terminal) {
                throw new IllegalStateException("终态后出现 onComplete");
            }
            terminal = true;
            completed.set(fullText);
            usage.set(completionUsage);
        }

        @Override
        public void onError(Throwable cause) {
            if (terminal) {
                throw new IllegalStateException("终态后出现 onError");
            }
            terminal = true;
            error.set(cause);
        }
    }
}
