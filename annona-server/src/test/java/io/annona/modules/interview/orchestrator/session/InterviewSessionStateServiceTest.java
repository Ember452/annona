package io.annona.modules.interview.orchestrator.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.interview.orchestrator.plan.InterviewPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 面试会话状态机编排：自动废弃旧会话、交卷 fencing 顺序（ADR 决策 6/M8——条件 UPDATE 是唯一
 * 守门，败者不得触碰作答行）、终态重放语义。条件 UPDATE 的 SQL 本体在 repository，真库并发
 * 由 docker 组 InterviewSessionFlowIT 钉死（本 slice 只证编排分支）。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
class InterviewSessionStateServiceTest {

    @Mock
    private InterviewSessionRepository sessionRepository;

    @Mock
    private InterviewAnswerRepository answerRepository;

    private InterviewSessionStateService service;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIRECTION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private InterviewSessionStateService newService() {
        return new InterviewSessionStateService(sessionRepository, answerRepository);
    }

    private static InterviewSessionEntity session(String status) {
        InterviewSessionEntity s = new InterviewSessionEntity();
        s.setId(UUID.randomUUID());
        s.setUserId(USER);
        s.setDirectionId(DIRECTION);
        s.setStatus(status);
        s.setTotalCount((short) 3);
        s.setCurrentIndex((short) 0);
        return s;
    }

    @Test
    @DisplayName("建会话：同方向旧 RESUMABLE 自动置 ABANDONED，新会话与整排作答占位落库")
    void createAbandonsPreviousResumable() {
        service = newService();
        when(sessionRepository.abandonAllResumable(eq(USER), eq(DIRECTION), any())).thenReturn(1);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        var plan = new InterviewPlan(2, List.of(3, 4), 0);
        var questions = List.of(UUID.randomUUID(), UUID.randomUUID());

        InterviewSessionEntity created = service.create(USER, DIRECTION, plan,
            List.of(new AnswerSlot(questions.get(0), 0), new AnswerSlot(questions.get(1), 0)));

        verify(sessionRepository).abandonAllResumable(eq(USER), eq(DIRECTION), any());
        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(InterviewSessionEntity.STATUS_RESUMABLE);
        assertThat(created.getPlanJson()).contains("\"totalCount\":2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InterviewAnswerEntity>> slots = ArgumentCaptor.forClass(List.class);
        verify(answerRepository).saveAll(slots.capture());
        assertThat(slots.getValue()).hasSize(2);
        assertThat(slots.getValue()).allSatisfy(a -> {
            assertThat(a.getAnswerStatus()).isEqualTo(InterviewAnswerEntity.STATUS_PENDING);
            assertThat(a.getAnswerText()).isNull();
        });
    }

    @Test
    @DisplayName("交卷赢者：预读在途后条件 UPDATE（含归属谓词）生效，才批量置 SUBMITTED 返回 v1")
    void finalizeWinnerSubmitsAll() {
        service = newService();
        var s = session(InterviewSessionEntity.STATUS_RESUMABLE);
        when(sessionRepository.findByIdAndUserId(s.getId(), USER)).thenReturn(Optional.of(s));
        when(sessionRepository.finalizeIfResumable(eq(s.getId()), eq(USER), anyString(), any()))
            .thenReturn(1);
        when(answerRepository.markAllSubmitted(eq(s.getId()), any())).thenReturn(3);

        var result = service.finalizeSession(s.getId(), USER);

        assertThat(result.evaluatorVersion()).isEqualTo("v1");
        assertThat(result.answeredCount()).isEqualTo(3);
        assertThat(result.sessionId()).isEqualTo(s.getId());
        verify(answerRepository).markAllSubmitted(eq(s.getId()), any());
    }

    @Test
    @DisplayName("交卷败者（并发）：预读在途但条件 UPDATE affected=0，绝不触碰作答行，重读抛 2702")
    void finalizeLosesRace() {
        service = newService();
        var s = session(InterviewSessionEntity.STATUS_RESUMABLE);
        var completed = session(InterviewSessionEntity.STATUS_COMPLETED);
        completed.setId(s.getId());
        when(sessionRepository.findByIdAndUserId(s.getId(), USER))
            .thenReturn(Optional.of(s), Optional.of(completed));
        when(sessionRepository.finalizeIfResumable(eq(s.getId()), eq(USER), anyString(), any()))
            .thenReturn(0);

        assertThatThrownBy(() -> service.finalizeSession(s.getId(), USER))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SESSION_ALREADY_COMPLETED.getCode()));
        verify(answerRepository, never()).markAllSubmitted(any(), any());
    }

    @Test
    @DisplayName("已交卷会话再次 finalize：不重复走转移，直接 2702")
    void finalizeOnCompletedThrows() {
        service = newService();
        var s = session(InterviewSessionEntity.STATUS_COMPLETED);
        when(sessionRepository.findByIdAndUserId(s.getId(), USER)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.finalizeSession(s.getId(), USER))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SESSION_ALREADY_COMPLETED.getCode()));
        verify(sessionRepository, never()).finalizeIfResumable(any(), any(), any(), any());
    }

    @Test
    @DisplayName("建会话：占位槽数与计划不符（客户端拼装越界）拒 1001，不落库")
    void createRejectsSlotMismatch() {
        service = newService();
        var plan = new InterviewPlan(2, List.of(3, 4), 0);   // 期望 2 个槽，给 3 个

        assertThatThrownBy(() -> service.create(USER, DIRECTION, plan,
            List.of(new AnswerSlot(UUID.randomUUID(), 0), new AnswerSlot(UUID.randomUUID(), 0),
                new AnswerSlot(UUID.randomUUID(), 1))))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("题内追问少于计划 depth 是合法输入（结构校验不按固定公式误杀）")
    void createAcceptsFewerFollowUpsThanDepth() {
        service = newService();
        when(sessionRepository.abandonAllResumable(eq(USER), eq(DIRECTION), any())).thenReturn(0);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        var plan = new InterviewPlan(2, List.of(3, 4), 2);   // depth=2，但 q1 只有 1 层追问
        var q1 = UUID.randomUUID();
        var q2 = UUID.randomUUID();

        var created = service.create(USER, DIRECTION, plan, List.of(
            new AnswerSlot(q1, 0), new AnswerSlot(q1, 1), new AnswerSlot(q2, 0)));

        assertThat(created.getStatus()).isEqualTo(InterviewSessionEntity.STATUS_RESUMABLE);
    }

    @Test
    @DisplayName("会话不存在/非本人：统一 2701，不泄漏归属差异")
    void missingSessionIs2701() {
        service = newService();
        when(sessionRepository.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeSession(UUID.randomUUID(), USER))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SESSION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("逐题作答透传条件 UPDATE 结果（0 行 = 槽位不符，由调用方给 2703）")
    void submitAnswerPassesThrough() {
        service = newService();
        var q = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        when(answerRepository.submitAnswer(eq(sessionId), eq(USER), eq(q), eq((short) 0),
            eq("答得很稳"), any())).thenReturn(1);

        assertThat(service.submitAnswer(sessionId, USER, q, 0, "答得很稳")).isTrue();
        verify(sessionRepository, never()).save(any());
    }
}
