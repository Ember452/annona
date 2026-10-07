package io.annona.modules.voice.handler;

import io.annona.common.voice.AsrListener;
import io.annona.common.voice.AsrOptions;
import io.annona.common.voice.StreamingAsrProvider;
import io.annona.common.voice.TtsOptions;
import io.annona.common.voice.TtsProvider;
import io.annona.modules.voice.audio.EchoGate;
import io.annona.modules.voice.audio.PcmWav;
import io.annona.modules.voice.dto.VoiceProtocol;
import io.annona.modules.voice.metrics.VoiceLatencyMetrics;
import io.annona.modules.voice.service.VoiceSessionService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.HtmlUtils;

/**
 * /ws/voice 消息路由与会话编排（P3-01，voice-adr §决策 3/5）。职责：协议分发、
 * 连接级状态机、ASR 会话生命周期（含一次重启恢复）、回声半双工、迟到转写丢弃、
 * 开场白 TTS 下发（TTS 缺席/失败即字幕降级）。持久化全部委托
 * {@link VoiceSessionService}（每方法一个短事务）；ASR/TTS 调用一律在事务外。
 *
 * <p>失败语义（客户端可见）：通道中断/帧不合法发 {@code error} 帧（码段 2600），
 * recoverable=true 表示降级或重试后可继续；ASR 通道中断后连接保持、转手动提交
 * （降级路径，P3 出口③）。日志不落用户转写文本（🅖 行为规格 shouldNotLogSttText，
 * voice-adr 隐私红线）。
 */
@Component
public class VoiceWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);

    private static final int CODE_CHANNEL_NOT_CONFIGURED = 2601;
    private static final int CODE_CHANNEL_INTERRUPTED = 2602;
    private static final int CODE_BAD_FRAME = 2603;

    /** ASR 通道故障后的一次重启尝试上限：重启仍失败即降级手动提交（🅖 restart 先例的最小版）。 */
    private static final int ASR_RESTART_ATTEMPTS = 1;

    private final VoiceSessionService sessions;
    private final VoiceLatencyMetrics metrics;
    private final Map<String, StreamingAsrProvider> asrProviders;
    private final Map<String, TtsProvider> ttsProviders;

    /** 连接级状态；键为 WS session id（连接唯一）。 */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(VoiceSessionService sessions, VoiceLatencyMetrics metrics,
                                 Map<String, StreamingAsrProvider> asrProviders,
                                 Map<String, TtsProvider> ttsProviders) {
        this.sessions = sessions;
        this.metrics = metrics;
        // Optional 注入语义在装配侧完成：provider=none 时 bean 缺席，此处收到的 map 不含该通道
        this.asrProviders = asrProviders;
        this.ttsProviders = ttsProviders;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession wsSession) {
        connections.put(wsSession.getId(), new Connection(userIdOf(wsSession)));
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession wsSession, @NonNull CloseStatus status) {
        Connection conn = connections.remove(wsSession.getId());
        if (conn != null && conn.voiceSessionId != null) {
            conn.finalized.set(true);
            closeAsrQuietly(conn);
            sessions.abandonIfOpen(conn.voiceSessionId, conn.userId);
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession wsSession, @NonNull TextMessage message) {
        Connection conn = connections.get(wsSession.getId());
        if (conn == null) {
            return;
        }
        VoiceProtocol.ClientFrame frame;
        try {
            frame = VoiceProtocol.parse(message.getPayload());
        } catch (IOException e) {
            try {
                send(conn, wsSession, VoiceProtocol.error(CODE_BAD_FRAME, "帧格式不合法", true));
            } catch (IOException ignored) {
                // 连接已死，无帧可发
            }
            return;
        }
        try {
            switch (frame.type()) {
                case "start" -> handleStart(conn, wsSession, frame);
                case "audio" -> handleAudio(conn, wsSession, frame);
                case "control" -> handleControl(conn, wsSession, frame);
                case "submit" -> handleSubmit(conn, wsSession, frame);
                case "ping" -> { /* 保活由 WS 协议层心跳承担；应用层 ping 静默容忍 */ }
                default -> send(conn, wsSession, VoiceProtocol.error(CODE_BAD_FRAME,
                    "未知帧类型: " + HtmlUtils.htmlEscape(frame.type()), true));
            }
        } catch (IOException e) {
            log.info("[voice] send failed (client likely gone): {}", e.toString());
        }
    }

    // ── start：建会话 + 开 ASR 通道 + 开场白 TTS ───────────────────────────

    private void handleStart(Connection conn, WebSocketSession wsSession, VoiceProtocol.ClientFrame frame)
        throws IOException {
        if (conn.voiceSessionId != null) {
            send(conn, wsSession, VoiceProtocol.error(CODE_BAD_FRAME, "会话已开始", true));
            return;
        }
        UUID directionId = frame.directionId() != null && !frame.directionId().isBlank()
            ? UUID.fromString(frame.directionId()) : null;

        StreamingAsrProvider asrProvider = asrProviders.isEmpty() ? null : asrProviders.values().iterator().next();
        TtsProvider ttsProvider = ttsProviders.isEmpty() ? null : ttsProviders.values().iterator().next();

        var session = sessions.create(conn.userId, directionId,
            asrProvider == null ? null : asrProvider.name(),
            ttsProvider == null ? null : ttsProvider.name());
        conn.voiceSessionId = session.getId();
        conn.opening = session.getOpening();

        if (asrProvider != null) {
            openAsr(conn, wsSession, asrProvider, 0);
        }
        send(conn, wsSession, VoiceProtocol.state(session.getStatus()));
        speak(conn, wsSession, ttsProvider, session.getOpening());
    }

    /** 打开一路 ASR 会话；onReady 由 provider 回调触发 ready 帧。 */
    private void openAsr(Connection conn, WebSocketSession wsSession,
                         StreamingAsrProvider asrProvider, int attempt) {
        try {
            conn.asr = asrProvider.start(AsrOptions.annonaDefault(), asrListener(conn, wsSession));
        } catch (RuntimeException e) {
            log.warn("[voice] ASR start failed (attempt {}): {}", attempt, e.toString());
            try {
                send(conn, wsSession, VoiceProtocol.error(CODE_CHANNEL_INTERRUPTED,
                    "语音识别通道启动失败，请使用文字输入", true));
            } catch (IOException ignored) {
                // 连接已死，无帧可发
            }
        }
    }

    /** ASR 回调适配：ready/partial/final/error → 下行帧 + 落库 + 埋点。 */
    private AsrListener asrListener(Connection conn, WebSocketSession wsSession) {
        return new AsrListener() {
            @Override
            public void onReady() {
                trySend(conn, wsSession, VoiceProtocol.ready());
            }

            @Override
            public void onPartial(String text) {
                if (conn.finalized.get()) {
                    return; // 迟到 partial 丢弃
                }
                recordFirstPartialIfNeeded(conn);
                trySend(conn, wsSession, VoiceProtocol.subtitle(text, false));
            }

            @Override
            public void onFinal(String text) {
                if (conn.finalized.get()) {
                    return; // 迟到定稿丢弃：不污染已收口会话（🅖 行为规格）
                }
                recordFirstPartialIfNeeded(conn);
                boolean persisted = sessions.appendTranscript(conn.voiceSessionId, conn.userId, text);
                if (persisted) {
                    trySend(conn, wsSession, VoiceProtocol.subtitle(text, true));
                }
            }

            @Override
            public void onError(Throwable cause) {
                log.warn("[voice] ASR channel error: {}", cause.toString());
                try {
                    degradeAsr(conn, wsSession);
                } catch (IOException e) {
                    log.info("[voice] degrade frame not sent (client likely gone): {}", e.toString());
                }
            }
        };
    }

    /** 首个 partial 埋点（每会话一次）：首帧上行音频 → 首个 partial。 */
    private void recordFirstPartialIfNeeded(Connection conn) {
        long firstAudio = conn.firstAudioAt.get();
        if (firstAudio > 0 && conn.firstPartialRecorded.compareAndSet(false, true)) {
            metrics.recordAsrFirstPartial(Duration.ofMillis(System.currentTimeMillis() - firstAudio));
        }
    }

    // ── audio：回声闸门 + 暂停丢弃 + ASR 故障重启 ──────────────────────────

    private void handleAudio(Connection conn, WebSocketSession wsSession, VoiceProtocol.ClientFrame frame)
        throws IOException {
        if (conn.voiceSessionId == null || conn.asr == null || conn.paused.get()) {
            return; // 未开始/降级/暂停：静默丢帧（暂停是主动行为，不该给用户红条）
        }
        if (frame.data() == null || frame.data().length() > VoiceProtocol.MAX_FRAME_CHARS) {
            send(conn, wsSession, VoiceProtocol.error(CODE_BAD_FRAME, "音频帧超限", true));
            return;
        }
        if (conn.echoGate.isMuted(Instant.now())) {
            return; // AI 播放期半双工：丢弃防自我循环
        }
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(frame.data());
        } catch (IllegalArgumentException e) {
            send(conn, wsSession, VoiceProtocol.error(CODE_BAD_FRAME, "音频帧不是合法 base64", true));
            return;
        }
        conn.firstAudioAt.compareAndSet(0L, System.currentTimeMillis());
        try {
            conn.asr.sendAudio(pcm);
        } catch (IllegalStateException e) {
            restartAsr(conn, wsSession);
        }
    }

    /** 通道故障的恢复路径：静默重启（用户无感）；重启仍失败才降级手动提交。 */
    private void restartAsr(Connection conn, WebSocketSession wsSession) throws IOException {
        closeAsrQuietly(conn);
        var provider = asrProviders.isEmpty() ? null : asrProviders.values().iterator().next();
        if (provider == null || conn.asrRestarts.get() >= ASR_RESTART_ATTEMPTS) {
            degradeAsr(conn, wsSession);
            return;
        }
        conn.asrRestarts.incrementAndGet();
        openAsr(conn, wsSession, provider, conn.asrRestarts.get());
    }

    /** 降级：ASR 通道置空，连接保持，客户端切手动提交（P3 出口③的 ASR 侧）。 */
    private void degradeAsr(Connection conn, WebSocketSession wsSession) throws IOException {
        closeAsrQuietly(conn);
        send(conn, wsSession, VoiceProtocol.error(CODE_CHANNEL_INTERRUPTED,
            "语音识别通道中断，已切换为文字输入", true));
    }

    // ── control / submit ─────────────────────────────────────────────────

    private void handleControl(Connection conn, WebSocketSession wsSession, VoiceProtocol.ClientFrame frame)
        throws IOException {
        if (conn.voiceSessionId == null) {
            send(conn, wsSession, VoiceProtocol.error(CODE_BAD_FRAME, "会话未开始", true));
            return;
        }
        switch (frame.action() == null ? "" : frame.action()) {
            case "pause" -> {
                if (sessions.pause(conn.voiceSessionId, conn.userId)) {
                    conn.paused.set(true);
                    send(conn, wsSession, VoiceProtocol.state("PAUSED"));
                }
            }
            case "resume" -> {
                if (sessions.resume(conn.voiceSessionId, conn.userId)) {
                    conn.paused.set(false);
                    send(conn, wsSession, VoiceProtocol.state("ACTIVE"));
                }
            }
            case "stop" -> {
                conn.finalized.set(true);
                closeAsrQuietly(conn);
                if (sessions.finalizeSession(conn.voiceSessionId, conn.userId)) {
                    send(conn, wsSession, VoiceProtocol.state("FINALIZED"));
                }
                wsSession.close(CloseStatus.NORMAL);
            }
            default -> send(conn, wsSession, VoiceProtocol.error(CODE_BAD_FRAME,
                "未知 control action: " + HtmlUtils.htmlEscape(String.valueOf(frame.action())), true));
        }
    }

    /** 手动提交文字（无耳机/ASR 降级场景的作答路径）：与 ASR 定稿同一落库通道。 */
    private void handleSubmit(Connection conn, WebSocketSession wsSession, VoiceProtocol.ClientFrame frame)
        throws IOException {
        if (conn.voiceSessionId == null) {
            send(conn, wsSession, VoiceProtocol.error(CODE_BAD_FRAME, "会话未开始", true));
            return;
        }
        boolean persisted = sessions.appendTranscript(conn.voiceSessionId, conn.userId, frame.text());
        if (persisted) {
            send(conn, wsSession, VoiceProtocol.subtitle(frame.text(), true));
        }
    }

    // ── 开场白播报：TTS 可用走音频（半双工窗口），不可用/失败走字幕 ─────────

    private void speak(Connection conn, WebSocketSession wsSession, TtsProvider ttsProvider, String text)
        throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        if (ttsProvider == null) {
            send(conn, wsSession, VoiceProtocol.text(text));
            return;
        }
        try {
            long start = System.currentTimeMillis();
            byte[] pcm = ttsProvider.synthesize(text, TtsOptions.defaults());
            metrics.recordTtsSynthesize(Duration.ofMillis(System.currentTimeMillis() - start));
            if (pcm.length == 0) {
                send(conn, wsSession, VoiceProtocol.text(text));
                return;
            }
            conn.echoGate.markSpeaking(pcm.length, 24000, Instant.now());
            String wav = Base64.getEncoder().encodeToString(PcmWav.wrap(pcm, 24000));
            send(conn, wsSession, VoiceProtocol.audioChunk(wav, 1, true));
            send(conn, wsSession, VoiceProtocol.text(text));
        } catch (RuntimeException e) {
            log.warn("[voice] TTS failed, falling back to subtitle: {}", e.toString());
            send(conn, wsSession, VoiceProtocol.text(text));
        }
    }

    // ── 发送与清理 ───────────────────────────────────────────────────────

    private void send(Connection conn, WebSocketSession wsSession, String json) throws IOException {
        synchronized (conn.sendLock) {
            wsSession.sendMessage(new TextMessage(json));
        }
    }

    private void trySend(Connection conn, WebSocketSession wsSession, String json) {
        try {
            send(conn, wsSession, json);
        } catch (IOException e) {
            log.info("[voice] send failed (client likely gone): {}", e.toString());
        }
    }

    private void closeAsrQuietly(Connection conn) {
        var conversation = conn.asr;
        conn.asr = null;
        if (conversation != null) {
            try {
                conversation.close();
            } catch (RuntimeException e) {
                log.debug("[voice] ASR close ignored: {}", e.toString());
            }
        }
    }

    /**
     * 握手属性里的主体 id 是 {@link io.annona.spi.dto.Principal#id()} 的<b>字符串</b>形态
     * （local/none 模式即 app_user.id 的 UUID 文本；CI docker-it 走 local）。platform 模式
     * 的 unionId 非 UUID，本批语音链路不支持——解析失败在这里显式炸掉连接，而不是让
     * 后续每条 SQL 拿着非法 id 撞约束。
     */
    private UUID userIdOf(WebSocketSession wsSession) {
        Object userId = wsSession.getAttributes().get(VoiceHandshakeInterceptor.ATTR_USER_ID);
        try {
            return UUID.fromString(String.valueOf(userId));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("voice: 握手主体不是合法用户 id（platform 模式暂不支持语音）", e);
        }
    }

    /** 连接级状态（一 WS 连接一路；字段单线程写（WS 消息线程）+ 多线程读（ASR 回调线程）， volatile 兜底）。 */
    private static final class Connection {
        final UUID userId;
        final EchoGate echoGate = new EchoGate();
        final AtomicBoolean paused = new AtomicBoolean(false);
        final AtomicBoolean finalized = new AtomicBoolean(false);
        final AtomicBoolean firstPartialRecorded = new AtomicBoolean(false);
        /** 首帧上行音频的毫秒时间戳（0 = 尚无）；partial 埋点起点。 */
        final java.util.concurrent.atomic.AtomicLong firstAudioAt = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger asrRestarts = new java.util.concurrent.atomic.AtomicInteger();
        final Object sendLock = new Object();
        volatile UUID voiceSessionId;
        volatile String opening;
        volatile StreamingAsrProvider.AsrConversation asr;

        Connection(UUID userId) {
            this.userId = userId;
        }
    }
}
