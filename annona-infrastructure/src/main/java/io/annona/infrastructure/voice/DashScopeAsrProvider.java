package io.annona.infrastructure.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.annona.common.voice.AsrListener;
import io.annona.common.voice.AsrOptions;
import io.annona.common.voice.StreamingAsrProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DashScope Omni realtime ASR 直连实现（qwen3-asr-flash-realtime，voice-adr §决策 2）。
 * 不引 dashscope-sdk-java：JDK {@link WebSocket} 承载 Omni 协议——握手鉴权
 * {@code Authorization: Bearer <key>} + {@code ?model=} 查询参数；客户端帧
 * {@code session.update}（VAD/转写配置）→ {@code input_audio_buffer.append}（base64 PCM）→
 * {@code session.finish}；服务端帧 {@code session.updated}（→onReady）、
 * {@code conversation.item.input_audio_transcription.delta/.text}（partial，text+stash 拼接）、
 * {@code .completed}（final）、{@code error}。事件名以 🅖 QwenAsrService 的服务端事件处理为准；
 * session.update 内字段形状若有出入，按手工验收教程的抓包步骤校准（ADR §否决的备选）。
 *
 * <p>线程模型：SDK 线程只做回调分发（轻），帧发送在调用线程 join 等待背压完成；
 * {@code sendAudio} 抛 {@link IllegalStateException} 即通道不可用，由编排层决定重启
 * （端口契约，🅖 先例：sendAudio 失败 → WS 层 restart，不在本层重试）。
 */
public class DashScopeAsrProvider implements StreamingAsrProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeAsrProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsrProperties properties;
    private final HttpClient httpClient;

    public DashScopeAsrProvider(AsrProperties properties) {
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
    public AsrConversation start(AsrOptions options, AsrListener listener) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("annona.model.asr.api-key 未配置（provider=dashscope）");
        }
        URI uri = URI.create(properties.getUrl() + "?model=" + properties.getModel());
        DashScopeConversation conversation = new DashScopeConversation(uri, options, listener);
        conversation.connect();
        return conversation;
    }

    /**
     * partial 文本提取（🅖 extractTranscriptPayload 的 Jackson 移植）：
     * transcript 直取 → text（确认前缀）+ stash（草稿后缀）拼接 → delta（字符串或对象）→
     * item.transcript。多形状兼容是 qwen-asr-realtime 服务端事件的既知现实。
     * 包级静态：协议形状规格测试直接钉这里。
     */
    static String extractTranscriptPayload(JsonNode root) {
        if (root.hasNonNull("transcript") && root.path("transcript").isTextual()) {
            return root.path("transcript").asText();
        }
        if (root.has("text") || root.has("stash")) {
            String combined = root.path("text").asText("") + root.path("stash").asText("");
            if (!combined.isBlank()) {
                return combined;
            }
        }
        JsonNode delta = root.path("delta");
        if (delta.isTextual()) {
            return delta.asText();
        }
        if (delta.isObject()) {
            if (delta.hasNonNull("text")) {
                return delta.path("text").asText();
            }
            if (delta.hasNonNull("transcript")) {
                return delta.path("transcript").asText();
            }
        }
        JsonNode item = root.path("item");
        if (item.isObject() && item.hasNonNull("transcript")) {
            return item.path("transcript").asText();
        }
        return null;
    }

    /** 一路 Omni realtime 会话：一个 JDK WebSocket + 一个监听适配器。 */
    private final class DashScopeConversation implements WebSocket.Listener, AsrConversation {

        private final URI uri;
        private final AsrOptions options;
        private final AsrListener listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean ready = new AtomicBoolean();
        private final AtomicReference<WebSocket> socketRef = new AtomicReference<>();
        private final StringBuilder textBuffer = new StringBuilder();

        DashScopeConversation(URI uri, AsrOptions options, AsrListener listener) {
            this.uri = uri;
            this.options = options;
            this.listener = listener;
        }

        void connect() {
            httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("user-agent", "annona-voice/1.0")
                .buildAsync(uri, this)
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        log.warn("[voice-asr] handshake failed: {}", error.toString());
                        listener.onError(error);
                    }
                });
        }

        // ── JDK WebSocket.Listener（回调线程） ──────────────────────────────

        @Override
        public void onOpen(WebSocket socket) {
            socketRef.set(socket);
            socket.sendText(sessionUpdateFrame(), true).join();
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
            log.info("[voice-asr] upstream closed: code={}, reason={}", statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            if (!closed.get()) {
                log.warn("[voice-asr] channel error: {}", error.toString());
                listener.onError(error);
            }
        }

        // ── 服务端事件分发 ────────────────────────────────────────────────

        private void handleServerFrame(String frame) {
            try {
                JsonNode root = MAPPER.readTree(frame);
                String type = root.path("type").asText("");
                switch (type) {
                    case "session.updated" -> {
                        if (ready.compareAndSet(false, true)) {
                            listener.onReady();
                        }
                    }
                    case "conversation.item.input_audio_transcription.completed" ->
                        listener.onFinal(root.path("transcript").asText(""));
                    case "conversation.item.input_audio_transcription.delta",
                         "conversation.item.input_audio_transcription.text" -> {
                        String partial = extractTranscriptPayload(root);
                        if (partial != null && !partial.isBlank()) {
                            listener.onPartial(partial);
                        }
                    }
                    case "error" -> {
                        String message = root.path("error").path("message").asText("unknown");
                        log.warn("[voice-asr] upstream error event: {}", message);
                        listener.onError(new IllegalStateException("ASR error: " + message));
                    }
                    case "session.created", "session.finished",
                         "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped",
                         "conversation.item.input_audio_transcription.failed" -> log.debug("[voice-asr] event: {}", type);
                    default -> log.debug("[voice-asr] unhandled event: {}", type);
                }
            } catch (Exception e) {
                log.warn("[voice-asr] failed to parse server frame: {}", e.toString());
            }
        }

        // ── 客户端帧构造 ──────────────────────────────────────────────────

        private String sessionUpdateFrame() {
            ObjectNode session = MAPPER.createObjectNode();
            session.putArray("modalities").add("text");
            // input_audio_format 形状是 ADR 登记的协议校准点之一，故做成属性：
            // 服务端若不认 "pcm,16000" 形态，改 yaml 即可，不用重编译
            session.put("input_audio_format", properties.getInputAudioFormat());
            ObjectNode transcription = session.putObject("input_audio_transcription");
            transcription.put("language", options.language());
            if ("manual".equals(properties.getVadType())) {
                session.putNull("turn_detection");
            } else {
                ObjectNode vad = session.putObject("turn_detection");
                vad.put("type", properties.getVadType());
                vad.put("threshold", properties.getVadThreshold());
                vad.put("silence_duration_ms", properties.getVadSilenceMs());
            }
            ObjectNode frame = MAPPER.createObjectNode();
            frame.put("type", "session.update");
            frame.set("session", session);
            return frame.toString();
        }

        // ── AsrConversation（调用线程） ───────────────────────────────────

        @Override
        public void sendAudio(byte[] pcm) {
            WebSocket socket = socketRef.get();
            if (closed.get() || socket == null || !ready.get()) {
                throw new IllegalStateException("ASR channel not ready");
            }
            String base64 = Base64.getEncoder().encodeToString(pcm);
            ObjectNode frame = MAPPER.createObjectNode();
            frame.put("type", "input_audio_buffer.append");
            frame.put("audio", base64);
            try {
                socket.sendText(frame.toString(), true).join();
            } catch (CompletionException e) {
                throw new IllegalStateException("ASR append failed", e.getCause() != null ? e.getCause() : e);
            } catch (RuntimeException e) {
                throw new IllegalStateException("ASR append failed", e);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            WebSocket socket = socketRef.get();
            if (socket == null) {
                return;
            }
            try {
                socket.sendText("{\"type\":\"session.finish\"}", true)
                    .thenRun(() -> socket.sendClose(WebSocket.NORMAL_CLOSURE, "done"))
                    .exceptionally(e -> {
                        socket.abort();
                        return null;
                    });
            } catch (RuntimeException e) {
                socket.abort();
            }
        }
    }
}
