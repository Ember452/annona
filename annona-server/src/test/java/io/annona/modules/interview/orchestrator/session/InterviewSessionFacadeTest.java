package io.annona.modules.interview.orchestrator.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.cache.SessionSnapshotPort;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.interview.orchestrator.controller.CreateSessionRequest;
import io.annona.modules.interview.orchestrator.pack.QuestionDedupService;
import io.annona.modules.interview.orchestrator.pack.QuestionPackService;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.question.QuestionCandidate;
import io.annona.shared.question.QuestionQueryService;
import io.annona.shared.question.QuestionStemDetail;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 会话编排：开始/作答/交卷/放弃/恢复读路径与快照维护（SQL 真行为归 docker IT）。 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewSessionFacadeTest {

    @Mock
    private InterviewSessionStateService stateService;
    @Mock
    private InterviewSessionRepository sessionRepository;
    @Mock
    private InterviewAnswerRepository answerRepository;
    @Mock
    private DirectionQueryService directionQuery;
    @Mock
    private QuestionQueryService questionQuery;
    @Mock
    private QuestionDedupService dedupService;
    @Mock
    private ObjectProvider<SessionSnapshotPort> snapshotProvider;
    @Mock
    private SessionSnapshotPort snapshot;

    private InterviewSessionFacade facade;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIRECTION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // QuestionPackService 用真实实现（纯逻辑，mock 它等于没测装配）
        facade = new InterviewSessionFacade(stateService, sessionRepository, answerRepository,
            directionQuery, questionQuery, dedupService, new QuestionPackService(),
            snapshotProvider);
        lenient().when(snapshotProvider.getIfAvailable()).thenReturn(snapshot);
        lenient().when(dedupService.findDedupHits(any(), any(), any())).thenReturn(Map.of());
        lenient().when(directionQuery.existsVisibleTo(anyString(), anyString())).thenReturn(true);
    }

    private static QuestionCandidate candidate(int seq, int difficulty, int followUps) {
        return new QuestionCandidate(
            UUID.nameUUIDFromBytes(("q" + seq).getBytes()), "题干" + seq, difficulty, followUps);
    }

    private static InterviewSessionEntity resumableSession(String planJson) {
        InterviewSessionEntity s = new InterviewSessionEntity();
        s.setId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        s.setUserId(USER);
        s.setDirectionId(DIRECTION);
        s.setStatus(InterviewSessionEntity.STATUS_RESUMABLE);
        s.setPlanJson(planJson);
        s.setTotalCount((short) 2);
        s.setCurrentIndex((short) 0);
        s.setStartedAt(java.time.Instant.now());
        return s;
    }

    @Test
    @DisplayName("开始面试：快照含执行定稿，槽位按追问数展平，视图与占位一致")
    void createPacksAndPersistsSnapshot() {
        var q1 = candidate(1, 3, 2);   // 2 个追问，depth=1 → 取 1
        var q2 = candidate(2, 4, 0);
        when(questionQuery.activePool(USER, DIRECTION)).thenReturn(List.of(q1, q2));
        when(questionQuery.stemsByIds(any())).thenReturn(List.of(
            new QuestionStemDetail(q1.id(), "题干1", List.of("追问1a", "追问1b")),
            new QuestionStemDetail(q2.id(), "题干2", List.of())));
        // 回显真实快照：stateService 落库的 planJson 就是视图装配的单一真相源
        when(stateService.create(eq(USER), eq(DIRECTION), anyString(), eq(2), any()))
            .thenAnswer(inv -> resumableSession(inv.getArgument(2, String.class)));

        var view = facade.create(USER, new CreateSessionRequest(DIRECTION.toString(),
            2, List.of(3, 4), 1));

        var snapshotJson = ArgumentCaptor.forClass(String.class);
        var slots = ArgumentCaptor.forClass(List.class);
        verify(stateService).create(eq(USER), eq(DIRECTION), snapshotJson.capture(),
            eq(2), slots.capture());
        assertThat(snapshotJson.getValue()).contains("questionIds").contains("\"totalCount\":2");
        assertThat(slots.getValue()).hasSize(3);   // q1 主+1追问, q2 主
        assertThat(view.slots()).hasSize(3);
        assertThat(view.slots().get(0).questionText()).isEqualTo("题干1");
        assertThat(view.slots().get(1).questionText()).isEqualTo("追问1a");
        assertThat(view.slots().get(1).followUpIndex()).isEqualTo(1);
        verify(snapshot).save(eq(view.id()), anyString());
    }

    @Test
    @DisplayName("计划非法（难度序列长度不符）→ 1001，不碰题库")
    void createRejectsBadPlan() {
        assertThatThrownBy(() -> facade.create(USER, new CreateSessionRequest(
            DIRECTION.toString(), 2, List.of(3), 1)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
        verify(questionQuery, never()).activePool(any(), any());
    }

    @Test
    @DisplayName("题池为空 → 2604（复用批 1 容量语义，M7）")
    void createRejectsEmptyPool() {
        when(questionQuery.activePool(USER, DIRECTION)).thenReturn(List.of());

        assertThatThrownBy(() -> facade.create(USER, new CreateSessionRequest(
            DIRECTION.toString(), 2, List.of(3, 4), 0)))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.QB_QUESTION_CAPACITY_INSUFFICIENT.getCode()));
    }

    @Test
    @DisplayName("去重全拦截 → 2604 且文案指向补题库")
    void createRejectsAllDeduped() {
        var q1 = candidate(1, 3, 0);
        when(questionQuery.activePool(USER, DIRECTION)).thenReturn(List.of(q1));
        when(dedupService.findDedupHits(any(), any(), any())).thenReturn(
            Map.of(q1.id(), new io.annona.modules.interview.orchestrator.pack.StemSimilarity(
                q1.id(), 0.95, false)));

        assertThatThrownBy(() -> facade.create(USER, new CreateSessionRequest(
            DIRECTION.toString(), 1, List.of(3), 0)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getCode())
                    .isEqualTo(ErrorCode.QB_QUESTION_CAPACITY_INSUFFICIENT.getCode());
                assertThat(e.getMessage()).contains("90");
            });
    }

    @Test
    @DisplayName("主问题作答成功：推进恢复位并失效快照")
    void answerAdvancesAndEvicts() {
        var q1 = candidate(1, 3, 0);
        var session = resumableSession(new ObjectMapper().valueToTree(new InterviewSessionFacade
            .PackSnapshot(1, List.of(3), 0, List.of(q1.id()), List.of())).toString());
        when(sessionRepository.findByIdAndUserId(session.getId(), USER))
            .thenReturn(Optional.of(session));
        when(stateService.submitAnswer(session.getId(), USER, q1.id(), 0, "答")).thenReturn(true);

        assertThat(facade.answer(USER, session.getId(), q1.id(), 0, "答")).isTrue();

        verify(sessionRepository).advanceIndexIfResumable(eq(session.getId()), eq((short) 1),
            any());
        verify(snapshot).evict(session.getId().toString());
    }

    @Test
    @DisplayName("作答失效快照延后到事务提交后（防提交前旧读回填缓存）")
    void answerDefersEvictUntilAfterCommit() {
        var q1 = candidate(1, 3, 0);
        var session = resumableSession(new ObjectMapper().valueToTree(new InterviewSessionFacade
            .PackSnapshot(1, List.of(3), 0, List.of(q1.id()), List.of())).toString());
        when(sessionRepository.findByIdAndUserId(session.getId(), USER))
            .thenReturn(Optional.of(session));
        when(stateService.submitAnswer(session.getId(), USER, q1.id(), 0, "答")).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(facade.answer(USER, session.getId(), q1.id(), 0, "答")).isTrue();
            // 提交前不得失效：否则并发 get() 会用旧 DB 状态回填缓存
            verify(snapshot, never()).evict(any());
            // 模拟容器提交成功：触发注册的 afterCommit 同步器
            for (TransactionSynchronization sync
                : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(snapshot).evict(session.getId().toString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("槽位不符（重复作答）→ 2703，不推进不失效")
    void answerSlotMismatchThrows2703() {
        var session = resumableSession("{\"questionIds\":[]}");
        when(sessionRepository.findByIdAndUserId(session.getId(), USER))
            .thenReturn(Optional.of(session));
        when(stateService.submitAnswer(any(), any(), any(), any(Integer.class).intValue(), any()))
            .thenReturn(false);

        assertThatThrownBy(() -> facade.answer(USER, session.getId(), UUID.randomUUID(), 0, "答"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SESSION_SLOT_MISMATCH.getCode()));
        verify(snapshot, never()).evict(any());
    }

    @Test
    @DisplayName("恢复读：归属校 DB 后快照命中直接返回（不查作答行）")
    void getUsesSnapshotAfterOwnershipCheck() throws Exception {
        var session = resumableSession("{\"questionIds\":[]}");
        when(sessionRepository.findByIdAndUserId(session.getId(), USER))
            .thenReturn(Optional.of(session));
        var cached = new io.annona.modules.interview.orchestrator.controller.SessionView(
            session.getId().toString(), DIRECTION.toString(), "RESUMABLE", 0, 2, 0,
            List.of(), List.of(), session.getStartedAt().toString());
        when(snapshot.get(session.getId().toString()))
            .thenReturn(Optional.of(MAPPER.writeValueAsString(cached)));

        var view = facade.get(USER, session.getId());

        assertThat(view.answeredCount()).isZero();
        verify(answerRepository, never())
            .findBySessionIdOrderByQuestionIdAscFollowUpIndexAsc(any());
    }

    @Test
    @DisplayName("恢复读 miss：DB 重建视图（题目已删时文案兜底）并回写快照")
    void getFallsBackToDb() throws Exception {
        var q1 = candidate(1, 3, 1);
        var session = resumableSession(MAPPER.writeValueAsString(
            new InterviewSessionFacade.PackSnapshot(1, List.of(3), 1, List.of(q1.id()),
                List.of("难度5 无可用题目（含相邻回填），跳过 1 个槽位"))));
        when(sessionRepository.findByIdAndUserId(session.getId(), USER))
            .thenReturn(Optional.of(session));
        when(snapshot.get(any())).thenReturn(Optional.empty());
        var answered = new InterviewAnswerEntity();
        answered.setQuestionId(q1.id());
        answered.setFollowUpIndex((short) 0);
        answered.setAnswerStatus(InterviewAnswerEntity.STATUS_SUBMITTED);
        answered.setAnswerText("我的答案");
        when(answerRepository.findBySessionIdOrderByQuestionIdAscFollowUpIndexAsc(
            session.getId())).thenReturn(List.of(answered));
        when(questionQuery.stemsByIds(any())).thenReturn(
            List.of(new QuestionStemDetail(q1.id(), "题干1", List.of("追问1"))));

        var view = facade.get(USER, session.getId());

        assertThat(view.slots()).hasSize(2);
        assertThat(view.slots().get(0).answered()).isTrue();
        assertThat(view.slots().get(0).answerText()).isEqualTo("我的答案");
        assertThat(view.slots().get(1).questionText()).isEqualTo("追问1");
        assertThat(view.skippedReasons()).hasSize(1);
        verify(snapshot).save(eq(session.getId().toString()), anyString());
    }

    @Test
    @DisplayName("已放弃会话再放弃 → 2702（区分文案），不触转移")
    void abandonTerminalThrows() {
        var session = resumableSession("{}");
        session.setStatus(InterviewSessionEntity.STATUS_ABANDONED);
        when(sessionRepository.findByIdAndUserId(session.getId(), USER))
            .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> facade.abandon(USER, session.getId()))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.SESSION_ALREADY_COMPLETED.getCode()));
        verify(stateService, never()).abandon(any(), any());
    }

    @Test
    @DisplayName("交卷透传幂等结果并失效快照")
    void finalizePassesThrough() {
        var sessionId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        when(stateService.finalizeSession(sessionId, USER)).thenReturn(
            new InterviewSessionStateService.FinalizeResult(sessionId, 5, "v1"));

        var view = facade.finalizeSession(USER, sessionId);

        assertThat(view.answeredCount()).isEqualTo(5);
        assertThat(view.status()).isEqualTo("COMPLETED");
        verify(snapshot).evict(sessionId.toString());
    }
}
