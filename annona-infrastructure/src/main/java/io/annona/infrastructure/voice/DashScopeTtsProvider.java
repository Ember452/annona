package io.annona.infrastructure.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.annona.common.voice.TtsOptions;
import io.annona.common.voice.TtsProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DashScope qwen-tts-realtime 直连实现（voice-adr §决策 2，一次性合成语义）。
 * 每次合成临时建连：{@code session.update}（音色/格式/commit 模式）→
 * {@code input_text_buffer.append} + {@code commit} → 收集 {@code response.audio.delta}
 * （base64 PCM 24kHz）直到 {@code response.done}。SDK 侧参数形状以 🅖 QwenTtsService 为准；
 * 帧名字段若有出入按手工验收教程校准（ADR §否决的备选）。
 *
 * <p>失败语义：握手失败<b>快速失败</b>（connectTimeout 内必返回，🅖 行为规格
 * shouldReturnPromptlyWhenWebSocketHandshakeFails）；合成超时/上游报错抛
 * {@link IllegalStateException} 交编排层降级为字幕——与 SDK 版"返空数组"不同：端口契约
 * 把"供应商坏了"和"没内容可合成"（空白文本返空数组）分开，编排层才能正确降级。
 */
public class DashScopeTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeTtsProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TtsProperties properties;
    private final HttpClient httpClient;

    public DashScopeTtsProvider(TtsProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String name() {
        return properties.getModel();
    }

    @Override
    public String channel() {
        return "dashscope";
    }

    @Override
    public byte[] synthesize(String text, TtsOptions options) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("annona.model.tts.api-key 未配置（provider=dashscope）");
        }
        try (SynthesisSession session = new SynthesisSession(options)) {
            return session.synthesize(text);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("TTS synthesis failed", e);
        }
    }

    /** 一次合成的连接生命周期（try-with-resources 保证关闭）。 */
    private final class SynthesisSession implements AutoCloseable, WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();
        private final java.io.ByteArrayOutputStream audio = new java.io.ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<WebSocket> socketRef = new AtomicReference<>();
        private final CountDownLatch handshake = new CountDownLatch(1);
        private final AtomicReference<Throwable> handshakeFailure = new AtomicReference<>();
        private final String voice;

        SynthesisSession(TtsOptions options) {
            this.voice = options != null && options.voice() != null && !options.voice().isBlank()
                ? options.voice() : properties.getVoice();
        }

        byte[] synthesize(String text) throws Exception {
            URI uri = URI.create(properties.getUrl() + "?model=" + properties.getModel());
            CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("user-agent", "annona-voice/1.0")
                .buildAsync(uri, this);
            future.whenComplete((socket, error) -> {
                if (error != null) {
                    handshakeFailure.compareAndSet(null, error);
                    handshake.countDown();
                }
            });
            // 握手快速失败：connectTimeout 内没有成功即抛（回调线程收 onOpen 时也会 countdown）
            if (!handshake.await(properties.getConnectTimeoutSeconds(), TimeUnit.SECONDS)) {
                future.cancel(true);
                throw new IllegalStateException(
                    "TTS handshake timed out after " + properties.getConnectTimeoutSeconds() + "s");
            }
            Throwable handshakeError = handshakeFailure.get();
            if (handshakeError != null) {
                throw new IllegalStateException("TTS handshake failed", handshakeError);
            }

            WebSocket socket = socketRef.get();
            socket.sendText(sessionUpdateFrame(), true).join();
            ObjectNode append = MAPPER.createObjectNode();
            append.put("type", "input_text_buffer.append");
            append.put("text", text);
            socket.sendText(append.toString(), true).join();
            socket.sendText("{\"type\":\"input_text_buffer.commit\"}", true).join();

            if (!done.await(properties.getSynthesizeTimeoutSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "TTS synthesis timed out after " + properties.getSynthesizeTimeoutSeconds() + "s");
            }
            Throwable error = failure.get();
            if (error != null) {
                throw new IllegalStateException("TTS synthesis failed", error);
            }
            byte[] pcm = audio.toByteArray();
            log.debug("[voice-tts] synthesized {} bytes for {} chars", pcm.length, text.length());
            return pcm;
        }

        // ── JDK WebSocket.Listener ────────────────────────────────────────

        @Override
        public void onOpen(WebSocket socket) {
            socketRef.set(socket);
            handshake.countDown();
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String frame = textBuffer.toString();
                textBuffer.setLength(0);
                handleServerFrame(frame);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            // 上游视 close 为完成信号： latch 兜底（response.done 丢失时不挂到超时）
            done.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            failure.compareAndSet(null, error);
            handshakeFailure.compareAndSet(null, error);
            handshake.countDown();
            done.countDown();
        }

        private void handleServerFrame(String frame) {
            try {
                JsonNode root = MAPPER.readTree(frame);
                String type = root.path("type").asText("");
                switch (type) {
                    case "response.audio.delta" -> {
                        String base64 = root.path("delta").asText("");
                        if (!base64.isEmpty()) {
                            byte[] chunk = Base64.getDecoder().decode(base64);
                            synchronized (audio) {
                                audio.write(chunk);
                            }
                        }
                    }
                    case "response.done" -> done.countDown();
                    case "error" -> {
                        failure.compareAndSet(null, new IllegalStateException(
                            "TTS error: " + root.path("error").path("message").asText("unknown")));
                        done.countDown();
                    }
                    case "session.created", "session.updated" -> log.debug("[voice-tts] event: {}", type);
                    default -> log.debug("[voice-tts] unhandled event: {}", type);
                }
            } catch (Exception e) {
                failure.compareAndSet(null, e);
                done.countDown();
            }
        }

        private String sessionUpdateFrame() {
            ObjectNode session = MAPPER.createObjectNode();
            session.putArray("modalities").add("audio");
            session.put("voice", voice);
            session.put("response_format", "pcm,24000");
            session.put("mode", "commit");
            if (properties.getLanguageType() != null && !properties.getLanguageType().isBlank()) {
                session.put("language_type", properties.getLanguageType());
            }
            session.put("speech_rate", properties.getSpeechRate());
            session.put("volume", properties.getVolume());
            ObjectNode frame = MAPPER.createObjectNode();
            frame.put("type", "session.update");
            frame.set("session", session);
            return frame.toString();
        }

        @Override
        public void close() {
            WebSocket socket = socketRef.get();
            if (socket != null) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                } catch (RuntimeException e) {
                    socket.abort();
                }
            }
        }
    }
}
