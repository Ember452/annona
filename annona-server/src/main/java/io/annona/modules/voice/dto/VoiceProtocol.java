package io.annona.modules.voice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/**
 * /ws/voice 帧协议（voice-adr §决策 5，🅖 voiceInterview.ts 形态）。
 * 全 JSON 文本帧：上行 5 种（start/audio/control/submit/ping），下行 6 种
 * （ready/subtitle/audio_chunk/text/state/error）。本类是协议的唯一编解码出处——
 * 纯静态、无 IO，协议测试直接钉这里。
 *
 * <p>错误码段 2600（语音段，ErrorCode 分段纪律）：2601 通道未装配、2602 通道中断、
 * 2603 帧不合法。批 1 走 WS 帧下发（不经 HTTP 异常出口）；出现 REST 端点时再进
 * ErrorCode 枚举（同一批同步）。
 */
public final class VoiceProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 上行帧大小上限（base64 字符数）：16k×16bit×200ms ≈ 8.5KB 裸帧 ≈ 11.4KB base64，
     * 留 4 倍余量；超限按 2603 拒绝，防恶意大帧占满 WS 线程。 */
    public static final int MAX_FRAME_CHARS = 65536;

    /** 客户端上行帧的松散形状：五种帧各取所需字段，其余为 null。 */
    public record ClientFrame(String type, String data, String action, String text, String directionId) {
    }

    private VoiceProtocol() {
    }

    /** 解析上行帧；非 JSON/缺 type 抛 IOException（调用方按 2603 处理）。 */
    public static ClientFrame parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject() || root.path("type").asText("").isBlank()) {
            throw new IOException("malformed frame: missing type");
        }
        return new ClientFrame(
            root.path("type").asText(),
            root.hasNonNull("data") ? root.path("data").asText() : null,
            root.hasNonNull("action") ? root.path("action").asText() : null,
            root.hasNonNull("text") ? root.path("text").asText() : null,
            root.hasNonNull("directionId") ? root.path("directionId").asText() : null);
    }

    /** ASR 通道就绪。 */
    public static String ready() {
        return frame("ready");
    }

    /** 转写字幕：isFinal=false 为中间草稿（会被后续帧取代），true 为 VAD 断句定稿。 */
    public static String subtitle(String text, boolean isFinal) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "subtitle");
        node.put("text", text);
        node.put("isFinal", isFinal);
        return node.toString();
    }

    /** 下行音频块（base64 WAV）；seq 单调递增，isLast 标记本句结束。 */
    public static String audioChunk(String base64Wav, int seq, boolean isLast) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "audio_chunk");
        node.put("data", base64Wav);
        node.put("seq", seq);
        node.put("isLast", isLast);
        return node.toString();
    }

    /** 面试官文本（TTS 不可用时的字幕降级路径，与 audio_chunk 同句配对或单独下发）。 */
    public static String text(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "text");
        node.put("content", content);
        return node.toString();
    }

    /** 会话状态广播（ACTIVE/PAUSED/FINALIZED）。 */
    public static String state(String status) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "state");
        node.put("status", status);
        return node.toString();
    }

    /** 错误帧（码段 2600；recoverable=true 表示客户端可重试/降级继续）。 */
    public static String error(int code, String message, boolean recoverable) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "error");
        node.put("code", code);
        node.put("message", message);
        node.put("recoverable", recoverable);
        return node.toString();
    }

    private static String frame(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        return node.toString();
    }
}
