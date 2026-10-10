package io.annona.modules.voice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.modules.voice.entity.VoiceSessionEntity;
import io.annona.modules.voice.entity.VoiceSessionMessageEntity;
import io.annona.modules.voice.repository.VoiceSessionMessageRepository;
import io.annona.modules.voice.repository.VoiceSessionRepository;
import io.annona.shared.domain.VoiceSessionFinalizedEvent;
import org.springframework.context.ApplicationEventPublisher;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link VoiceSessionService} 切片：快照落库、条件迁移透传、空白转写短路。
 * 状态机在真库上的行为（条件 UPDATE 影响行数守门）由 VoiceFlowIT 钉。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("VoiceSessionService 切片")
class VoiceSessionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private VoiceSessionRepository repository;
    @Mock
    private VoiceSessionMessageRepository messages;
    @Mock
    private ApplicationEventPublisher events;

    private VoiceSessionService service;

    @BeforeEach
    void setUp() {
        service = new VoiceSessionService(repository, messages, events);
    }

    @Test
    @DisplayName("create：ACTIVE 状态 + 开场白/题库队列/ASR/TTS 快照落库")
    void createSnapshots() {
        service.create(USER_ID, null, "asr-model", "tts-model", "[\"qid\"]", "自定义开场白");

        var captor = ArgumentCaptor.forClass(VoiceSessionEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getOpening()).isEqualTo("自定义开场白");
        assertThat(saved.getAsrModel()).isEqualTo("asr-model");
        assertThat(saved.getTtsModel()).isEqualTo("tts-model");
        assertThat(saved.getQuestionIds()).isEqualTo("[\"qid\"]");
        assertThat(saved.getTranscript()).isEmpty();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("通道未装配/开场白缺省时快照为 null 与默认开场白")
    void createWithNullModels() {
        service.create(USER_ID, null, null, null, "[]", null);
        var captor = ArgumentCaptor.forClass(VoiceSessionEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAsrModel()).isNull();
        assertThat(captor.getValue().getTtsModel()).isNull();
        assertThat(captor.getValue().getOpening()).isEqualTo(VoiceSessionService.DEFAULT_OPENING);
    }

    @Test
    @DisplayName("appendTurn：ANSWER 轮按 seq 递增落库并关联题目")
    void appendsAnswerTurn() {
        var sessionId = UUID.randomUUID();
        when(messages.findTopBySessionIdOrderBySeqDesc(sessionId)).thenReturn(Optional.empty());

        service.appendTurn(sessionId, VoiceSessionMessageEntity.ROLE_ANSWER, null, "第一轮作答");

        var captor = ArgumentCaptor.forClass(io.annona.modules.voice.entity.VoiceSessionMessageEntity.class);
        verify(messages).save(captor.capture());
        assertThat(captor.getValue().getSeq()).isZero();
        assertThat(captor.getValue().getRole()).isEqualTo("ANSWER");
    }

    @Test
    @DisplayName("收口赢者发布 VoiceSessionFinalizedEvent（评估接入的触发源）")
    void finalizePublishesEvent() {
        var sessionId = UUID.randomUUID();
        var session = new VoiceSessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        when(repository.findByIdAndUserId(sessionId, USER_ID)).thenReturn(Optional.of(session));
        when(repository.finalizeIfOpen(eq(sessionId), eq(USER_ID), any())).thenReturn(1);

        assertThat(service.finalizeSession(sessionId, USER_ID)).isTrue();
        verify(events).publishEvent(any(VoiceSessionFinalizedEvent.class));
    }

    @Test
    @DisplayName("收口败者（已终态）不发事件")
    void finalizeLoserSkipsEvent() {
        var sessionId = UUID.randomUUID();
        var session = new VoiceSessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        when(repository.findByIdAndUserId(sessionId, USER_ID)).thenReturn(Optional.of(session));
        when(repository.finalizeIfOpen(eq(sessionId), eq(USER_ID), any())).thenReturn(0);

        assertThat(service.finalizeSession(sessionId, USER_ID)).isFalse();
        verify(events, never()).publishEvent(any(VoiceSessionFinalizedEvent.class));
    }

    @Test
    @DisplayName("appendTranscript：空白文本短路，不触库")
    void blankTranscriptShortCircuits() {
        assertThat(service.appendTranscript(UUID.randomUUID(), USER_ID, "   ")).isFalse();
        verify(repository, never()).appendTranscript(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("appendTranscript：非空白透传条件 UPDATE（追加换行由 SQL 层拼接）")
    void appendsViaRepository() {
        var sessionId = UUID.randomUUID();
        when(repository.appendTranscript(eq(sessionId), eq(USER_ID), eq("一句话\n"), any()))
            .thenReturn(1);

        assertThat(service.appendTranscript(sessionId, USER_ID, "一句话")).isTrue();
        verify(repository).appendTranscript(eq(sessionId), eq(USER_ID), eq("一句话\n"), any());
    }

    @Test
    @DisplayName("pause/resume 透传条件 UPDATE 的守门结果；会话不属该用户时收口直接 false")
    void transitionsDelegate() {
        var sessionId = UUID.randomUUID();
        when(repository.pauseIfActive(eq(sessionId), eq(USER_ID), any())).thenReturn(1);
        when(repository.resumeIfPaused(eq(sessionId), eq(USER_ID), any())).thenReturn(0);
        when(repository.findByIdAndUserId(sessionId, USER_ID)).thenReturn(Optional.empty());

        assertThat(service.pause(sessionId, USER_ID)).isTrue();
        assertThat(service.resume(sessionId, USER_ID)).isFalse();
        assertThat(service.finalizeSession(sessionId, USER_ID))
            .as("收口先校归属，再看条件 UPDATE").isFalse();
        org.mockito.Mockito.verifyNoInteractions(events);
    }

    @Test
    @DisplayName("断连兜底 abandonIfOpen 不上抛（幂等条件 UPDATE）")
    void abandonToleratesZeroRows() {
        var sessionId = UUID.randomUUID();
        when(repository.abandonIfOpen(eq(sessionId), eq(USER_ID), any())).thenReturn(0);
        service.abandonIfOpen(sessionId, USER_ID);
        verify(repository).abandonIfOpen(eq(sessionId), eq(USER_ID), any());
    }
}
