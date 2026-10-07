package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.service.AuthUserRegistrar;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 语音链路真库集测（P3-01，CI docker-it 专属）：真 WS 握手（Cookie 鉴权）→ fake
 * ASR/TTS 全链路 → voice_session 落库。本机测不到而必须真库钉的：握手鉴权、
 * 协议帧序、转写追加的条件 UPDATE（appendTranscript 的 concat 原位写）、
 * 收口状态机；fake 脚本的确定性让帧断言可写。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"annona.model.asr.provider=fake", "annona.model.tts.provider=fake"})
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("voice：WS 语音链路真库集测（P3-01）")
class VoiceFlowIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private AuthUserRegistrar registrar;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 注册 + 登录换取会话 Cookie（WS 握手凭据与浏览器同路径）。 */
    private String loginCookie() throws Exception {
        AuthUserResponse user = registrar.register("p3-" + java.util.UUID.randomUUID() + "@example.test",
            "GoodPass123");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(
                    Map.of("email", user.email(), "password", "GoodPass123"))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("set-cookie")
            .map(value -> value.split(";", 2)[0])
            .orElseThrow(() -> new AssertionError("login 未下发会话 Cookie"));
    }

    /** 收帧客户端：完整帧入列表；onClose 触发闭锁（服务端 stop 后主动关连接）。 */
    private static final class CollectingListener implements WebSocket.Listener {
        final List<String> frames = new ArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public synchronized CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                frames.add(buffer.toString());
                buffer.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.countDown();
            return null;
        }
    }

    private static JsonNode awaitFrame(List<String> frames, Predicate<JsonNode> predicate,
                                       String what) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            synchronized (frames) {
                for (String raw : frames) {
                    try {
                        JsonNode node = MAPPER.readTree(raw);
                        if (predicate.test(node)) {
                            return node;
                        }
                    } catch (Exception ignored) {
                        // 坏帧由协议错误路径覆盖，此处只做形状断言
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待帧超时: " + what + "；已收到 " + frames);
    }

    @Test
    @DisplayName("fake 全链路：握手→开场白播报→两帧出字幕→手动作答→收口落库")
    void voiceFullFlow() throws Exception {
        String cookie = loginCookie();
        CollectingListener listener = new CollectingListener();
        WebSocket ws = http.newWebSocketBuilder()
            .header("Cookie", cookie)
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/voice"), listener)
            .get(5, TimeUnit.SECONDS);

        // start → ready（fake ASR 同步就绪）→ state ACTIVE → 开场白音频 + 字幕
        ws.sendText("{\"type\":\"start\"}", true);
        awaitFrame(listener.frames, n -> "ready".equals(n.path("type").asText()), "ready");
        awaitFrame(listener.frames, n -> "state".equals(n.path("type").asText())
            && "ACTIVE".equals(n.path("status").asText()), "state ACTIVE");
        JsonNode chunk = awaitFrame(listener.frames, n -> "audio_chunk".equals(n.path("type").asText()),
            "audio_chunk");
        assertThat(chunk.path("seq").asInt()).isEqualTo(1);
        assertThat(chunk.path("isLast").asBoolean()).isTrue();
        JsonNode opening = awaitFrame(listener.frames, n -> "text".equals(n.path("type").asText()), "text");
        assertThat(opening.path("content").asText()).isNotBlank();

        // 开场白音频触发了回声半双工静音窗（100ms 音频 ×1.5 + 300ms 冷却 ≈ 450ms）：
        // 窗内上行会被正确丢弃——真实用户不可能在开场白后 100ms 内开口，测试同样等过窗口
        Thread.sleep(600);

        // 两帧出一句：partial → final（FakeAsr 确定性脚本）
        ws.sendText("{\"type\":\"audio\",\"data\":\"" + base64Of(64) + "\"}", true);
        awaitFrame(listener.frames, n -> "subtitle".equals(n.path("type").asText())
            && !n.path("isFinal").asBoolean(), "subtitle partial");
        ws.sendText("{\"type\":\"audio\",\"data\":\"" + base64Of(64) + "\"}", true);
        JsonNode finalSubtitle = awaitFrame(listener.frames, n -> "subtitle".equals(n.path("type").asText())
            && n.path("isFinal").asBoolean(), "subtitle final");
        assertThat(finalSubtitle.path("text").asText()).contains("第1句");

        // 手动作答与 ASR 定稿同一落库通道
        ws.sendText("{\"type\":\"submit\",\"text\":\"手动作答一句话\"}", true);
        awaitFrame(listener.frames, n -> "subtitle".equals(n.path("type").asText())
            && "手动作答一句话".equals(n.path("text").asText()), "manual subtitle");

        // 收口：state FINALIZED → 服务端关连接；条件 UPDATE 幂等守门在真库上生效
        ws.sendText("{\"type\":\"control\",\"action\":\"stop\"}", true);
        awaitFrame(listener.frames, n -> "state".equals(n.path("type").asText())
            && "FINALIZED".equals(n.path("status").asText()), "state FINALIZED");
        assertThat(listener.closed.await(5, TimeUnit.SECONDS)).isTrue();

        Integer finalized = jdbc.queryForObject(
            "select count(*) from voice_session where status = 'FINALIZED'"
                + " and transcript like '%手动作答一句话%' and asr_model = 'fake-asr'"
                + " and tts_model = 'fake-tts' and finalized_at is not null",
            Integer.class);
        assertThat(finalized).as("收口会话带全量快照与转写落库").isEqualTo(1);
    }

    @Test
    @DisplayName("无凭据握手 401 拒绝升级（身份门与 /api/** 同源）")
    void handshakeWithoutCookieRejected() {
        var future = http.newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/voice"), new CollectingListener());
        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        future.whenComplete((ws, error) -> {
            if (error != null) {
                thrown.set(error);
            }
        });
        var deadline = Instant.now().plusSeconds(5);
        while (thrown.get() == null && !future.isDone() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(future.isDone()).isTrue();
        assertThat(thrown.get()).isNotNull();
    }

    private static String base64Of(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) i;
        }
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
