package io.annona.modules.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.evaluation.entity.InterviewReportEntity;
import io.annona.modules.evaluation.model.GradeBatch;
import io.annona.modules.evaluation.repository.InterviewEvaluationRepository;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import io.annona.shared.ai.StructuredOutputInvoker;
import io.annona.shared.interview.EvalAnswer;
import io.annona.shared.interview.InterviewEvalQueryService;
import io.annona.shared.question.QuestionGrading;
import io.annona.shared.question.QuestionQueryService;
import io.annona.spi.model.ModelProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 评估消费的降级与幂等语义切片（出口③的行为规格）：三类畸形/缺字段输出都落 {@code fallback_used=true}
 * 且保留模型原文，正常输出落分数；瞬时失败重投。LLM 真调用与全链在 docker 组证伪，本切片只锁
 * "输出→落库分支"的映射，用 mock {@link StructuredOutputInvoker} 注入构造。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EvaluationService：畸形输出降级保留原文、正常输出落分、瞬时失败重投")
class EvaluationServiceTest {

    @Mock
    private InterviewReportRepository reportRepository;
    @Mock
    private InterviewEvaluationRepository evaluationRepository;
    @Mock
    private InterviewEvalQueryService evalQuery;
    @Mock
    private QuestionQueryService questionQuery;
    @Mock
    private ObjectProvider<StructuredOutputInvoker> invokerProvider;
    @Mock
    private ObjectProvider<ModelProvider> modelProvider;
    @Mock
    private StructuredOutputInvoker invoker;
    @Mock
    private PlatformTransactionManager transactionManager;

    private EvaluationService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID questionId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new EvaluationService(reportRepository, evaluationRepository, evalQuery,
            questionQuery, invokerProvider, modelProvider, transactionManager);

        InterviewReportEntity pending = InterviewReportEntity.pending(sessionId, userId, "v2",
            Instant.now());
        when(reportRepository.findBySessionIdAndEvaluatorVersion(sessionId, "v2"))
            .thenReturn(Optional.of(pending));
        when(reportRepository.tryMarkRunning(eq(sessionId), eq("v2"), any())).thenReturn(1);
        when(evalQuery.submittedAnswers(sessionId, userId))
            .thenReturn(List.of(new EvalAnswer(questionId, 0, "我的作答")));
        when(questionQuery.gradingByIds(any())).thenReturn(List.of(
            new QuestionGrading(questionId, "题干", "参考答案", List.of("关键点"), "评分标准", 3,
                List.of())));
        when(evaluationRepository.findBySessionIdAndEvaluatorVersionOrderByQuestionIdAscFollowUpIndexAsc(
            sessionId, "v2")).thenReturn(List.of());
        when(invokerProvider.getIfAvailable()).thenReturn(invoker);
        ModelProvider mp = mock(ModelProvider.class);
        lenient().when(mp.name()).thenReturn("glm-x");
        lenient().when(modelProvider.getIfAvailable()).thenReturn(mp);
        lenient().when(modelProvider.getObject()).thenReturn(mp);
    }

    private TaskStreamPort.Outcome handle(int retryCount) {
        return service.handle("m-1",
            Map.of("sessionId", sessionId.toString(), TaskStreamPort.RETRY_COUNT_FIELD,
                String.valueOf(retryCount)),
            retryCount);
    }

    @Test
    @DisplayName("非 JSON 输出 → 降级、score null、保留模型原文，报告仍置 DONE")
    void nonJsonFallsBackKeepingRaw() {
        when(invoker.invokeWithRaw(anyString(), anyString(), eq(GradeBatch.class)))
            .thenThrow(new StructuredOutputInvoker.StructuredOutputUnparsedException(
                "结构化输出解析失败", "这不是 JSON"));

        assertThat(handle(0)).isEqualTo(TaskStreamPort.Outcome.ACK);

        ArgumentCaptor<Short> score = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Boolean> fallback = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(evaluationRepository).upsert(any(), eq(sessionId), eq(questionId), anyShort(),
            eq("v2"), score.capture(), anyString(), anyString(), anyString(), fallback.capture(),
            raw.capture(), any());
        assertThat(score.getValue()).isNull();
        assertThat(fallback.getValue()).isTrue();
        assertThat(raw.getValue()).isEqualTo("这不是 JSON");
        verify(reportRepository).markDone(eq(sessionId), eq("v2"), any(), anyString(),
            anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("缺字段（score 为 null）→ 视为降级，保留原文")
    void missingScoreFallsBack() {
        GradeBatch batch = new GradeBatch(
            List.of(new GradeBatch.QuestionGrade(0, null, "点评", List.of(), List.of())));
        when(invoker.invokeWithRaw(anyString(), anyString(), eq(GradeBatch.class)))
            .thenReturn(new StructuredOutputInvoker.StructuredResult<>(batch, "{\"index\":0}"));

        handle(0);

        ArgumentCaptor<Boolean> fallback = ArgumentCaptor.forClass(Boolean.class);
        verify(evaluationRepository).upsert(any(), eq(sessionId), eq(questionId), anyShort(),
            eq("v2"), any(), anyString(), anyString(), anyString(), fallback.capture(), any(), any());
        assertThat(fallback.getValue()).isTrue();
    }

    @Test
    @DisplayName("正常输出 → 落分数、非降级、不存原文")
    void validOutputStoresScore() {
        GradeBatch batch = new GradeBatch(List.of(
            new GradeBatch.QuestionGrade(0, 80, "很好", List.of("强"), List.of("建议"))));
        when(invoker.invokeWithRaw(anyString(), anyString(), eq(GradeBatch.class)))
            .thenReturn(new StructuredOutputInvoker.StructuredResult<>(batch, "{\"grades\":...}"));

        handle(0);

        ArgumentCaptor<Short> score = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Boolean> fallback = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(evaluationRepository).upsert(any(), eq(sessionId), eq(questionId), anyShort(),
            eq("v2"), score.capture(), anyString(), anyString(), anyString(), fallback.capture(),
            raw.capture(), any());
        assertThat(score.getValue()).isEqualTo((short) 80);
        assertThat(fallback.getValue()).isFalse();
        assertThat(raw.getValue()).isNull();
    }

    @Test
    @DisplayName("瞬时失败（非解析类异常）→ 回退 PENDING 并重投，不置 DONE")
    void transientFailureRetries() {
        when(invoker.invokeWithRaw(anyString(), anyString(), eq(GradeBatch.class)))
            .thenThrow(new RuntimeException("网络抖动"));
        when(reportRepository.markRunningBackToPending(eq(sessionId), eq("v2"), any())).thenReturn(1);

        assertThat(handle(0)).isEqualTo(TaskStreamPort.Outcome.RETRY);
        verify(reportRepository, never()).markDone(any(), anyString(), any(), anyString(),
            anyString(), anyString(), anyString(), any());
    }
}
