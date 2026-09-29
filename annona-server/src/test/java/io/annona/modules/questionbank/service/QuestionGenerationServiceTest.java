package io.annona.modules.questionbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.interview.skill.service.SkillQueryService;
import io.annona.modules.knowledge.ops.KnowledgeDocQueryService;
import io.annona.modules.questionbank.entity.QbGenerationTaskEntity;
import io.annona.modules.questionbank.entity.QbQuestionEntity;
import io.annona.modules.questionbank.model.QuestionGenConfig;
import io.annona.modules.questionbank.repository.QbGenerationTaskRepository;
import io.annona.modules.questionbank.repository.QbQuestionRepository;
import io.annona.shared.ai.StructuredOutputInvoker;
import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.progress.SseProgressHub;
import io.annona.spi.dto.RetrievalHit;
import io.annona.spi.retrieval.Retriever;
import java.util.List;
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
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 出题执行器核心语义：去重/跳过计数、追问裁剪、替换草稿保留 ACTIVE、来源快照
 * （LLM 与检索的真实交互由 CI docker-it 与人工 demo 承担）。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionGenerationServiceTest {

    @Mock
    private QbGenerationTaskRepository taskRepository;
    @Mock
    private QbQuestionRepository questionRepository;
    @Mock
    private QuestionGenStateService stateService;
    @Mock
    private DirectionQueryService directionQuery;
    @Mock
    private SkillQueryService skillQuery;
    @Mock
    private KnowledgeDocQueryService knowledgeDocQuery;
    @Mock
    private Retriever retriever;
    @Mock
    private ObjectProvider<StructuredOutputInvoker> invokerProvider;
    @Mock
    private SseProgressHub progressHub;
    @Mock
    private PlatformTransactionManager transactionManager;

    @org.mockito.Captor
    private org.mockito.ArgumentCaptor<List<io.annona.modules.questionbank.entity.QbQuestionEntity>> listCaptor;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID DIRECTION = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID CHUNK = UUID.randomUUID();
    private static final String TASK_ID = UUID.randomUUID().toString();

    private QuestionGenerationService service;
    private StructuredOutputInvoker invoker;
    private QbGenerationTaskEntity task;

    @BeforeEach
    void setUp() {
        invoker = mock(StructuredOutputInvoker.class);
        lenient().when(invokerProvider.getObject()).thenReturn(invoker);
        lenient().when(stateService.tryMarkProcessing(UUID.fromString(TASK_ID))).thenReturn(true);
        lenient().when(directionQuery.findVisible(USER.toString(), DIRECTION.toString()))
            .thenReturn(Optional.of(new DirectionResponse(DIRECTION.toString(), "java-backend",
                "Java 后端", "SKILL_BUILTIN", DOC.toString(), "ACTIVE", null)));
        lenient().when(skillQuery.find("java-backend")).thenReturn(Optional.empty());
        lenient().when(knowledgeDocQuery.status(USER.toString(), DOC.toString()))
            .thenReturn(new io.annona.modules.knowledge.dto.KbDocStatusResponse("READY", "", 3, 3, ""));
        lenient().when(retriever.retrieve(any()))
            .thenReturn(List.of(new RetrievalHit(DOC.toString(), CHUNK.toString(), "片段", 0.9)));
        lenient().when(knowledgeDocQuery.chunkReferences(eq(USER.toString()), any()))
            .thenReturn(List.of(new io.annona.modules.knowledge.dto.KbChunkReference(
                DOC.toString(), CHUNK.toString(), 0, "Java > 集合", "HashMap 的树化阈值是 8。")));
        lenient().when(questionRepository.findRecentQuestions(any(), any(), any(Pageable.class)))
            .thenReturn(List.of());
        // TransactionTemplate 直跑（真事务由 docker-it 承担）
        lenient().when(transactionManager.getTransaction(any()))
            .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));

        task = new QbGenerationTaskEntity();
        task.setId(UUID.fromString(TASK_ID));
        task.setUserId(USER);
        task.setDirectionId(DIRECTION);
        task.setConfig(new QuestionGenConfig(3, 10, 2));
        lenient().when(taskRepository.findById(UUID.fromString(TASK_ID)))
            .thenReturn(Optional.of(task));
        service = new QuestionGenerationService(taskRepository, questionRepository, stateService,
            directionQuery, skillQuery, knowledgeDocQuery, retriever, invokerProvider, progressHub,
            transactionManager);
    }

    private QuestionGenerationService.QuestionListPayload payload(
        List<QuestionGenerationService.ParsedQuestion> questions) {
        return new QuestionGenerationService.QuestionListPayload(questions);
    }

    private QuestionGenerationService.ParsedQuestion question(String text, int followUps) {
        return new QuestionGenerationService.ParsedQuestion(text, "摘要", "参考答案",
            List.of("关键点"), "评分标准",
            java.util.stream.IntStream.range(0, followUps)
                .mapToObj(i -> new io.annona.modules.questionbank.model.QbFollowUp(
                    "追问" + i, "答案", List.of("点"), "标准"))
                .toList());
    }

    @Test
    @DisplayName("正常路径：全部有效题落库为 DRAFT，完成落账并广播进度")
    void generatesAndCompletes() {
        when(invoker.invoke(anyString(), anyString(), any())).thenReturn(
            payload(List.of(question("Q1 什么是fail-fast", 3), question("Q2 HashMap树化", 2))));

        service.run(UUID.fromString(TASK_ID));

        var captor = listCaptor;
        verify(questionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
            .allSatisfy(q -> {
                assertThat(q.getStatus()).isEqualTo(QbQuestionEntity.STATUS_DRAFT);
                assertThat(q.getDifficulty()).isEqualTo((short) 3);
                assertThat(q.getSources()).hasSize(1);
                assertThat(q.getSources().get(0).chunkId()).isEqualTo(CHUNK);
            });
        verify(stateService).markCompleted(UUID.fromString(TASK_ID), 2, 0, "已生成 2 题");
        verify(progressHub).publish(eq(DIRECTION),
            eq(new io.annona.shared.progress.ProgressEvent("COMPLETED", "出题完成", 2, 10, "已生成 2 题")));
    }

    @Test
    @DisplayName("空题干与重复题干跳过；追问裁剪到目标数；旧 DRAFT 被替换")
    void dedupsSkipsAndTrims() {
        when(questionRepository.findRecentQuestions(any(), any(), any(Pageable.class)))
            .thenReturn(List.of("Q1 既有题"));
        when(invoker.invoke(anyString(), anyString(), any())).thenReturn(
            payload(List.of(question("Q1 既有题", 2), question("Q1 既有题", 2),
                question("   ", 1), question("Q2 有效题", 4))));

        service.run(UUID.fromString(TASK_ID));

        var captor = listCaptor;
        verify(questionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getQuestion()).isEqualTo("Q2 有效题");
        // 追问 4 个裁到目标 2 个
        assertThat(captor.getValue().get(0).getFollowUps()).hasSize(2);
        verify(questionRepository).deleteDrafts(USER, DIRECTION);
        verify(stateService).markCompleted(UUID.fromString(TASK_ID), 1, 3,
            "已生成 1 题，跳过 3 道重复或无效题");
    }

    @Test
    @DisplayName("方向不可见 → BusinessException 2100（handler 层转 RETRY/DEAD）")
    void invisibleDirectionFails() {
        when(directionQuery.findVisible(any(), any())).thenReturn(Optional.empty());

        assertThatException(() -> service.run(UUID.fromString(TASK_ID)),
            io.annona.common.exception.ErrorCode.DIRECTION_NOT_FOUND);
        verify(stateService, never()).markCompleted(any(), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @DisplayName("重试语义：未耗尽 → resetForRetry + RETRY；耗尽 → markFailed + DEAD")
    void handlerRetryDecision() {
        when(invoker.invoke(anyString(), anyString(), any())).thenThrow(
            new BusinessException(io.annona.common.exception.ErrorCode.AI_SERVICE_ERROR));
        when(stateService.resetForRetry(UUID.fromString(TASK_ID))).thenReturn(true);

        TaskStreamPort.Outcome first = service.handle("msg", java.util.Map.of("taskId", TASK_ID), 0);
        assertThat(first).isEqualTo(TaskStreamPort.Outcome.RETRY);
        verify(stateService).resetForRetry(UUID.fromString(TASK_ID));

        TaskStreamPort.Outcome last = service.handle("msg", java.util.Map.of("taskId", TASK_ID),
            QuestionGenerationService.MAX_RETRY);
        assertThat(last).isEqualTo(TaskStreamPort.Outcome.DEAD);
        verify(stateService).markFailed(eq(UUID.fromString(TASK_ID)), anyString());
    }

    @Test
    @DisplayName("领取失败（已被其他实例处理）安静 ACK")
    void claimFailureAcks() {
        when(stateService.tryMarkProcessing(UUID.fromString(TASK_ID))).thenReturn(false);
        TaskStreamPort.Outcome outcome = service.handle("msg", java.util.Map.of("taskId", TASK_ID), 0);
        assertThat(outcome).isEqualTo(TaskStreamPort.Outcome.ACK);
        verify(invokerProvider, never()).getObject();
    }

    private static void assertThatException(Runnable runnable, io.annona.common.exception.ErrorCode code) {
        try {
            runnable.run();
            org.assertj.core.api.Assertions.fail("应当抛出 " + code);
        } catch (BusinessException e) {
            org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(code.getCode());
        }
    }
}
