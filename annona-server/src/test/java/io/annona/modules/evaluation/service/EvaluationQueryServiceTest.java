package io.annona.modules.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.modules.evaluation.entity.InterviewReportEntity;
import io.annona.modules.evaluation.repository.InterviewEvaluationRepository;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link EvaluationQueryService} 归属守卫回归（L3 安全审查建议补）：报告按
 * sessionId+evaluatorVersion 取回后必须过 owner 过滤——非 owner 与不存在同样报
 * 3000，不泄露他人会话是否存在；PDF 导出复用本方法，守卫自动同口径。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluationQueryService：报告归属守卫")
class EvaluationQueryServiceTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INTRUDER = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Mock
    private InterviewReportRepository reportRepository;

    @Mock
    private InterviewEvaluationRepository evaluationRepository;

    private EvaluationQueryService service;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        service = new EvaluationQueryService(reportRepository, evaluationRepository);
        sessionId = UUID.randomUUID();
    }

    @Test
    @DisplayName("跨用户读他人会话报告 → 3000，且不回读逐题明细（拒绝在取数前分支）")
    void crossUserReadIsRejected() {
        InterviewReportEntity other = InterviewReportEntity.pending(sessionId, OWNER,
            EvaluationService.EVALUATOR_VERSION, Instant.now());
        when(reportRepository.findBySessionIdAndEvaluatorVersion(
            eq(sessionId), eq(EvaluationService.EVALUATOR_VERSION))).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.report(INTRUDER, sessionId))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(3000));
        verify(evaluationRepository, never())
            .findBySessionIdAndEvaluatorVersionOrderByQuestionIdAscFollowUpIndexAsc(any(), any());
    }

    @Test
    @DisplayName("owner 正路径可读（守卫不是无条件拒绝的空转断言）")
    void ownerReadSucceeds() {
        InterviewReportEntity own = InterviewReportEntity.pending(sessionId, OWNER,
            EvaluationService.EVALUATOR_VERSION, Instant.now());
        own.setUpdatedAt(Instant.now());
        when(reportRepository.findBySessionIdAndEvaluatorVersion(
            eq(sessionId), eq(EvaluationService.EVALUATOR_VERSION))).thenReturn(Optional.of(own));
        when(evaluationRepository.findBySessionIdAndEvaluatorVersionOrderByQuestionIdAscFollowUpIndexAsc(
            sessionId, EvaluationService.EVALUATOR_VERSION)).thenReturn(List.of());

        var response = service.report(OWNER, sessionId);

        assertThat(response.sessionId()).isEqualTo(sessionId.toString());
        assertThat(response.status()).isEqualTo(InterviewReportEntity.STATUS_PENDING);
    }
}
