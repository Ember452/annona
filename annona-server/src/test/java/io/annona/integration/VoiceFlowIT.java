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
import java.util.UUID;
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
        return loginCookie(registrar.register("p3-" + java.util.UUID.randomUUID() + "@example.test",
            "GoodPass123"));
    }

    private String loginCookie(AuthUserResponse user) throws Exception {
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

        // 开场白之后现在紧跟着一个面试官轮（P3-03 对话轮）：先等到它的 text 帧，再按
        // “最后一段 AI 音频”算回声窗——否则 sleep 只盖住开场白，后到的回采句会把上行吃掉
        awaitTextFrames(listener.frames, 2);

        // 回声半双工静音窗（4800 字节 ≈ 100ms 音频 ×1.5 + 300ms 冷却 ≈ 450ms）：
        // 窗内上行会被正确丢弃——真实用户不可能在面试官说完 100ms 内开口，测试同样等过窗口
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

    // ── 批 2：题库驱动的对话轮（P3-03/05）──────────────────────────────

    /** 已收帧里的 text 帧个数（开场白与每个面试官轮各一条）。 */
    private static long countTextFrames(List<String> frames) {
        return frames.stream().filter(raw -> {
            try {
                return "text".equals(MAPPER.readTree(raw).path("type").asText());
            } catch (Exception e) {
                return false;
            }
        }).count();
    }

    private static void awaitTextFrames(List<String> frames, int expected) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            synchronized (frames) {
                if (countTextFrames(frames) >= expected) {
                    return;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待第 " + expected + " 条 text 帧超时；已收到 " + frames);
    }

    private UUID insertDirection(UUID userId, String name) {
        UUID id = java.util.UUID.randomUUID();
        jdbc.update("insert into direction (id, key, name, origin, status, user_id)"
                + " values (?, ?, ?, 'USER_CUSTOM', 'ACTIVE', ?)",
            id, "it-voice-" + id, name, userId);
        return id;
    }

    private UUID insertQuestion(UUID userId, UUID directionId, String stem) {
        UUID id = java.util.UUID.randomUUID();
        jdbc.update("insert into qb_question (id, user_id, direction_id, question,"
                + " key_points, difficulty, follow_ups, sources, status, updated_at)"
                + " values (?, ?, ?, ?, '[]'::jsonb, 3, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', now())",
            id, userId, directionId, stem);
        return id;
    }

    @Test
    @DisplayName("对话轮：题库队列→QUESTION/ANSWER 交替落 voice_message，收口后建 VOICE 报告")
    void turnQueuePersistsAndFinalizes() throws Exception {
        AuthUserResponse user = registrar.register(
            "p3-q-" + java.util.UUID.randomUUID() + "@example.test", "GoodPass123");
        UUID userId = UUID.fromString(user.id());
        UUID directionId = insertDirection(userId, "IT 语音轮次方向");
        List<UUID> fixtureQuestions = List.of(
            insertQuestion(userId, directionId, "语音轮题干一？"),
            insertQuestion(userId, directionId, "语音轮题干二？"));
        CollectingListener listener = new CollectingListener();
        WebSocket ws = http.newWebSocketBuilder()
            .header("Cookie", loginCookie(user))
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/voice"), listener)
            .get(5, TimeUnit.SECONDS);

        // 开场：快照题目队列（V19）→ 开场白 text → 第一个面试官轮 text
        ws.sendText(MAPPER.writeValueAsString(Map.of("type", "start",
            "directionId", directionId.toString())), true);
        awaitFrame(listener.frames, n -> "ready".equals(n.path("type").asText()), "ready");
        awaitTextFrames(listener.frames, 1);   // 开场白
        awaitTextFrames(listener.frames, 2);   // 面试官轮 0

        // 两轮作答：键入即收本轮（语音作答同走 finishAnswerTurn，只差在答案来源）
        ws.sendText(MAPPER.writeValueAsString(Map.of("type", "submit",
            "text", "我的作答一")), true);
        awaitTextFrames(listener.frames, 3);   // 面试官轮 1
        ws.sendText(MAPPER.writeValueAsString(Map.of("type", "submit", "text", "我的作答二")), true);
        awaitTextFrames(listener.frames, 4);   // 队列耗尽→结束语

        ws.sendText("{\"type\":\"control\",\"action\":\"stop\"}", true);
        awaitFrame(listener.frames, n -> "state".equals(n.path("type").asText())
            && "FINALIZED".equals(n.path("status").asText()), "state FINALIZED");

        // 队列快照与进度：2 题都来自夹具（activePool 时序倒排，不断具体次序），答完两题后推到 2
        String idsJson = jdbc.queryForObject(
            "select question_ids::text from voice_session where user_id = ?", String.class, userId);
        JsonNode snapshot = MAPPER.readTree(idsJson);
        assertThat(snapshot.size()).as("队列按 question-count 截取夹具两题").isEqualTo(2);
        assertThat(idsJson)
            .as("队列只能来自该方向的 ACTIVE 题池")
            .contains(fixtureQuestions.get(0).toString(), fixtureQuestions.get(1).toString());
        Integer progress = jdbc.queryForObject(
            "select current_question_seq from voice_session where user_id = ?", Integer.class, userId);
        assertThat(progress).as("两轮作答后指向队列尾").isEqualTo(2);

        // 轮次交替：Q/A/Q/A/Q，末轮是结束语（不关联题目→不进评分）；ANSWER 的题 id 与
        // 紧邻的上一 QUESTION 对齐——这是 P3-05 "同题库同 rubric 可比"的数据地基
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select seq, role, question_id, content from voice_message"
                + " where session_id = (select id from voice_session where user_id = ?)"
                + " order by seq asc", userId);
        assertThat(rows).extracting(r -> String.valueOf(r.get("role")))
            .containsExactly("QUESTION", "ANSWER", "QUESTION", "ANSWER", "QUESTION");
        assertThat(rows).filteredOn(r -> "ANSWER".equals(r.get("role")))
            .extracting(r -> String.valueOf(r.get("content")))
            .containsExactly("我的作答一", "我的作答二");
        assertThat(rows.get(1).get("question_id"))
            .as("ANSWER 轮对齐它作答的那道题")
            .isEqualTo(rows.get(0).get("question_id"));
        assertThat(rows.get(3).get("question_id")).isEqualTo(rows.get(2).get("question_id"));
        assertThat(rows.get(2).get("question_id"))
            .as("两轮问答覆盖队列里的两道不同的题")
            .isNotEqualTo(rows.get(0).get("question_id"));
        assertThat(rows.get(4).get("question_id")).as("结束语不关联题目").isNull();

        // 收口事件→同一评估引擎：报告行以 voice_session 为宿主（V21 多态化），
        // session_type=VOICE；状态到哪个阶段取决于评估流是否投递，本用例只钉存活性
        Map<String, Object> report = jdbc.queryForMap(
            "select session_type, status, user_id from interview_report where session_id = ("
                + "select id from voice_session where user_id = ?)", userId);
        assertThat(report.get("session_type")).isEqualTo("VOICE");
        assertThat(String.valueOf(report.get("user_id"))).isEqualTo(userId.toString());
        assertThat(String.valueOf(report.get("status")))
            .isIn("PENDING", "RUNNING", "DONE", "FAILED");
        // stop 已让服务端正常关连接（测试 1 同样不显式关），此处不需补 close
    }
}
