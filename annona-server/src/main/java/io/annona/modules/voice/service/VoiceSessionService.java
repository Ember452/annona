package io.annona.modules.voice.service;

import io.annona.modules.voice.entity.VoiceSessionEntity;
import io.annona.modules.voice.entity.VoiceSessionMessageEntity;
import io.annona.modules.voice.repository.VoiceSessionMessageRepository;
import io.annona.modules.voice.repository.VoiceSessionRepository;
import io.annona.shared.domain.VoiceSessionFinalizedEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 语音会话状态机编排（P3-01）。全部迁移走 repository 条件 UPDATE——本类没有
 * "find→setStatus→save" 通道（全仓状态机纪律，voice-adr §决策 3）。事务只包本地库写；
 * ASR/TTS/WS 推送都在调用方（WebSocket handler）的事务之外（AGENTS §0.3）。
 */
@Service
public class VoiceSessionService {

    /** 默认开场白：批 1 无配置端点，落库快照后批 2 换配置化来源（P3_VOICE_PLAN 批 2）。 */
    static final String DEFAULT_OPENING = "你好，我是本次的 AI 面试官。请戴上耳机以获得最佳效果；"
        + "准备好了就点开始，用语音回答我的问题。";

    private final VoiceSessionRepository repository;
    private final VoiceSessionMessageRepository messages;
    private final ApplicationEventPublisher events;

    public VoiceSessionService(VoiceSessionRepository repository,
                               VoiceSessionMessageRepository messages,
                               ApplicationEventPublisher events) {
        this.repository = repository;
        this.messages = messages;
        this.events = events;
    }

    /**
     * 建会话并直接进入 ACTIVE（批 1 无独立 CREATED 阶段的消费方；状态枚举保留 CREATED
     * 供批 2 的会话预检使用）。返回实体的 {@code createdAt} 为 null：DB default 列未回读
     * （save/merge 语义，AGENTS §4），本批消费方不需要它。
     *
     * @param asrModel 本会话 ASR 模型快照；通道未装配（降级手动提交）时传 null
     * @param ttsModel 本会话 TTS 模型快照；通道未装配（降级纯字幕）时传 null
     */
    @Transactional
    public VoiceSessionEntity create(UUID userId, UUID directionId, String asrModel, String ttsModel,
                                     String questionIdsJson, String opening) {
        VoiceSessionEntity session = new VoiceSessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setDirectionId(directionId);
        session.setStatus(VoiceSessionEntity.STATUS_ACTIVE);
        session.setOpening(opening == null || opening.isBlank() ? DEFAULT_OPENING : opening);
        session.setAsrModel(asrModel);
        session.setTtsModel(ttsModel);
        session.setQuestionIds(questionIdsJson);
        session.setTranscript("");
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return session;
    }

    /** 暂停（仅 ACTIVE 可暂停）；false = 会话不存在/非本人/状态不符，调用方不下发状态帧。 */
    @Transactional
    public boolean pause(UUID sessionId, UUID userId) {
        return repository.pauseIfActive(sessionId, userId, Instant.now()) > 0;
    }

    /** 恢复（仅 PAUSED 可恢复）。 */
    @Transactional
    public boolean resume(UUID sessionId, UUID userId) {
        return repository.resumeIfPaused(sessionId, userId, Instant.now()) > 0;
    }

    /**
     * 收口（幂等守门）：ACTIVE/PAUSED → FINALIZED；赢者发布
     * {@link VoiceSessionFinalizedEvent}（事务内发布，AFTER_COMMIT 消费——evaluation
     * 建 VOICE 报告并投递评估，与 interview 交卷同口径）。
     */
    @Transactional
    public boolean finalizeSession(UUID sessionId, UUID userId) {
        var session = repository.findByIdAndUserId(sessionId, userId).orElse(null);
        if (session == null) {
            return false;
        }
        if (repository.finalizeIfOpen(sessionId, userId, Instant.now()) <= 0) {
            return false;
        }
        events.publishEvent(new VoiceSessionFinalizedEvent(sessionId, userId, session.getDirectionId()));
        return true;
    }

    /** 追加一轮（ANSWER/QUESTION）；seq 取会话内最大 +1（单连接串行推进，无竞争面）。 */
    @Transactional
    public void appendTurn(UUID sessionId, String role, UUID questionId, String content) {
        int nextSeq = messages.findTopBySessionIdOrderBySeqDesc(sessionId)
            .map(m -> m.getSeq() + 1).orElse(0);
        var turn = VoiceSessionMessageEntity.ROLE_ANSWER.equals(role)
            ? VoiceSessionMessageEntity.answer(sessionId, nextSeq, questionId, content)
            : VoiceSessionMessageEntity.question(sessionId, nextSeq, questionId, content);
        messages.save(turn);
    }

    /** 推进题目下标（仅 ACTIVE；轮落库后调用）。 */
    @Transactional
    public void advanceQuestionSeq(UUID sessionId, UUID userId, int seq) {
        repository.advanceQuestionSeq(sessionId, userId, seq, Instant.now());
    }

    /** 断连未收口的兜底：置 ABANDONED（幂等）。 */
    @Transactional
    public void abandonIfOpen(UUID sessionId, UUID userId) {
        repository.abandonIfOpen(sessionId, userId, Instant.now());
    }

    /**
     * 追加一句 VAD 定稿转写（顺序拼接）。false = 会话已终态或不存在——调用方据此丢弃
     * 迟到转写（🅖 行为规格 shouldIgnoreLatePartialAfterPreviousTurnWasSubmitted 的落库侧）。
     */
    @Transactional
    public boolean appendTranscript(UUID sessionId, UUID userId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return repository.appendTranscript(sessionId, userId, text + "\n", Instant.now()) > 0;
    }
}
