package io.annona.modules.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.model.ChatMessage;
import io.annona.common.model.ChatStreamListener;
import io.annona.common.model.StreamingChatProvider;
import io.annona.modules.knowledge.dto.KbChunkReference;
import io.annona.modules.knowledge.ops.KnowledgeDocQueryService;
import io.annona.modules.qa.dto.QaAskRequest;
import io.annona.modules.qa.dto.QaCitation;
import io.annona.modules.qa.entity.QaMessageEntity;
import io.annona.modules.qa.entity.QaSessionEntity;
import io.annona.modules.qa.mapper.QaMapper;
import io.annona.modules.qa.repository.QaMessageRepository;
import io.annona.modules.qa.repository.QaSessionRepository;
import io.annona.modules.retrieval.dto.RetrievalResponse;
import io.annona.modules.retrieval.service.RetrievalQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * QaService 的 Mockito 切片：占位→条件回填生命周期、断线保留部分内容、引用组装与空命中
 * 透传、provider 缺席的 fail-fast。执行器用同步直跑（Runnable::run），事务管理器 mock
 * （TransactionTemplate 逻辑真实执行，KnowledgeDocLifecycleServiceTest 同口径）。
 * 回填走仓储的条件 UPDATE（见 backfill 注释的竞态取舍），故断言的是 backfill 调用参数
 * 而非实体保存。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("QaService：占位→条件回填生命周期与引用组装")
class QaServiceTest {

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String DOC_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHUNK_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    @Mock
    private QaSessionRepository sessionRepository;

    @Mock
    private QaMessageRepository messageRepository;

    @Mock
    private RetrievalQueryService retrievalQueryService;

    @Mock
    private KnowledgeDocQueryService docQueryService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private QaMapper mapper;

    private final List<QaMessageEntity> saved = new ArrayList<>();

    /** 每次 save 时的行状态快照（type/content/completed）：占位态只能从追加式快照观察。 */
    private final List<String[]> states = new ArrayList<>();

    @Captor
    private ArgumentCaptor<List<QaCitation>> citationsCaptor;

    @BeforeEach
    void setUp() {
        // save/merge 约定：返回受管副本，这里原样返回入参即可覆盖编排语义
        lenient().when(messageRepository.save(any())).thenAnswer(inv -> {
            QaMessageEntity row = inv.getArgument(0);
            saved.add(row);
            states.add(new String[] {row.getType(), row.getContent(), String.valueOf(row.isCompleted())});
            return row;
        });
        lenient().when(messageRepository.findMaxOrder(any(UUID.class))).thenReturn(0);
        lenient().when(messageRepository.backfill(any(UUID.class), any(), anyList(), eq(true), any()))
            .thenReturn(1);
        lenient().when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private QaService service(StreamingChatProvider provider) {
        return new QaService(sessionRepository, messageRepository, retrievalQueryService,
            docQueryService, Optional.ofNullable(provider), Runnable::run, transactionManager, mapper);
    }

    /** 确定性 provider：按给定脚本回调。占位态的观察走 states 快照，无需在流开始时再取。 */
    private static final class StubProvider implements StreamingChatProvider {

        private final List<String> deltas;
        private final boolean fail;
        private List<ChatMessage> seenMessages;

        private StubProvider(List<String> deltas, boolean fail) {
            this.deltas = deltas;
            this.fail = fail;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public void streamChat(List<ChatMessage> messages, ChatStreamListener listener) {
            this.seenMessages = messages;
            deltas.forEach(listener::onDelta);
            if (fail) {
                listener.onError(new BusinessException(ErrorCode.AI_STREAM_INTERRUPTED, "上游断了"));
            } else {
                listener.onComplete(String.join("", deltas));
            }
        }
    }

    private void stubRetrieval(String chunkId, boolean empty) {
        List<RetrievalResponse.Hit> hits = empty ? List.of()
            : List.of(new RetrievalResponse.Hit(DOC_ID, chunkId, 0.9));
        when(retrievalQueryService.search(eq(USER), any())).thenReturn(new RetrievalResponse(
            5L, hits, new RetrievalResponse.Diagnostics(1, 1,
                empty ? io.annona.modules.retrieval.dto.RetrievalMissReason.NO_MATCH
                    : io.annona.modules.retrieval.dto.RetrievalMissReason.MATCHED)));
        when(docQueryService.chunkReferences(eq(USER), any())).thenReturn(empty ? List.of()
            : List.of(new KbChunkReference(DOC_ID, chunkId, 3, "Java > 基础", "分块正文内容。")));
    }

    @Nested
    @DisplayName("占位→条件回填生命周期")
    class Lifecycle {

        @Test
        @DisplayName("一次提问落 2 行 + 一次条件回填：USER 行、ASSISTANT 空占位、回填完整回答")
        void placeholderThenBackfill() {
            stubRetrieval(CHUNK_ID, false);
            QaService service = service(new StubProvider(List.of("第一", "段"), false));

            service.ask(USER, new QaAskRequest(null, "什么是封装？"));

            assertThat(saved).hasSize(2);
            assertThat(saved.get(0).getType()).isEqualTo(QaMessageEntity.TYPE_USER);
            assertThat(saved.get(0).getMessageOrder()).isEqualTo(1);
            assertThat(saved.get(0).getContent()).isEqualTo("什么是封装？");
            assertThat(saved.get(0).isCompleted()).isTrue();

            // 占位态（快照）：USER + 空占位（completed=false），随后才发生流式
            assertThat(states.get(0)).containsExactly(QaMessageEntity.TYPE_USER, "什么是封装？", "true");
            assertThat(states.get(1)).containsExactly(QaMessageEntity.TYPE_ASSISTANT, "", "false");

            verify(messageRepository).backfill(eq(saved.get(1).getId()), eq("第一段"),
                citationsCaptor.capture(), eq(true), isNull());
            assertThat(citationsCaptor.getValue()).hasSize(1);
            assertThat(citationsCaptor.getValue().get(0).chunkId()).isEqualTo(CHUNK_ID);
        }

        @Test
        @DisplayName("首问新建会话：标题取问题前 20 字，会话行随短事务①落库")
        void newSessionTitledFromQuestion() {
            stubRetrieval(CHUNK_ID, false);
            AtomicReference<QaSessionEntity> sessionRef = new AtomicReference<>();
            when(sessionRepository.save(any())).thenAnswer(inv -> {
                sessionRef.set(inv.getArgument(0));
                return inv.getArgument(0);
            });

            service(new StubProvider(List.of("好"), false))
                .ask(USER, new QaAskRequest(null, "这是一个远超二十个字符长度的问题，应该被截断在这里之后"));

            assertThat(sessionRef.get().getTitle()).hasSize(20);
        }

        @Test
        @DisplayName("流中途失败：条件回填保留部分内容（completed=false、citations 为 null）")
        void interruptionKeepsPartialContent() {
            stubRetrieval(CHUNK_ID, false);
            QaService service = service(new StubProvider(List.of("部分回答，", "然后断了"), true));

            service.ask(USER, new QaAskRequest(null, "问题"));

            UUID assistantId = saved.get(1).getId();
            verify(messageRepository).backfill(eq(assistantId), eq("部分回答，然后断了"),
                isNull(), eq(false), isNull());
        }

        @Test
        @DisplayName("provider 未配置：占位行保持未完成，不发生回填（2502 经 error 事件下发）")
        void missingProviderKeepsPlaceholder() {
            stubRetrieval(CHUNK_ID, false);
            QaService service = service(null);

            service.ask(USER, new QaAskRequest(null, "问题"));

            assertThat(saved).hasSize(2); // 只有 USER 行与占位
            assertThat(saved.get(1).isCompleted()).isFalse();
            verify(messageRepository, never()).backfill(any(UUID.class), any(), anyList(), eq(true), any());
        }
    }

    @Nested
    @DisplayName("引用组装与透传")
    class Citations {

        @Test
        @DisplayName("引用顺序 = 检索 score 序；命中与回查之间被删的分块静默跳过")
        void citationsFollowHitOrderAndSkipMissing() {
            String ghostChunk = "dddddddd-dddd-dddd-dddd-dddddddddddd";
            when(retrievalQueryService.search(eq(USER), any())).thenReturn(new RetrievalResponse(5L,
                List.of(new RetrievalResponse.Hit(DOC_ID, CHUNK_ID, 0.9),
                    new RetrievalResponse.Hit(DOC_ID, ghostChunk, 0.7)),
                new RetrievalResponse.Diagnostics(1, 1,
                    io.annona.modules.retrieval.dto.RetrievalMissReason.MATCHED)));
            when(docQueryService.chunkReferences(eq(USER), any())).thenReturn(List.of(
                new KbChunkReference(DOC_ID, ghostChunk, 9, "丢失节", "这一条应被跳过"),
                new KbChunkReference(DOC_ID, CHUNK_ID, 3, "Java > 基础", "分块正文内容。")));

            service(new StubProvider(List.of("答"), false)).ask(USER, new QaAskRequest(null, "问题"));

            verify(messageRepository).backfill(any(UUID.class), eq("答"), citationsCaptor.capture(), eq(true), isNull());
            List<QaCitation> citations = citationsCaptor.getValue();
            assertThat(citations).hasSize(2);
            assertThat(citations.get(0).chunkId()).isEqualTo(CHUNK_ID);
            assertThat(citations.get(0).chunkIndex()).isEqualTo(3);
            assertThat(citations.get(0).snippet()).contains("分块正文内容");
            assertThat(citations.get(1).chunkId()).isEqualTo(ghostChunk);
        }

        @Test
        @DisplayName("空命中：仍走完整流（空上下文 + 空引用），诊断 reason 随回填持久化")
        void emptyHitsStillStreamWithReason() {
            stubRetrieval(CHUNK_ID, true);

            service(new StubProvider(List.of("资料里没有"), false)).ask(USER, new QaAskRequest(null, "冷门问题"));

            verify(messageRepository).backfill(any(UUID.class), eq("资料里没有"), eq(List.of()), eq(true),
                eq("NO_MATCH"));
            verify(docQueryService).chunkReferences(eq(USER), any());
        }

        @Test
        @DisplayName("拼进 user prompt 的上下文带资料编号与注入防御分隔")
        void contextIsNumberedAndFenced() {
            stubRetrieval(CHUNK_ID, false);
            StubProvider provider = new StubProvider(List.of("答"), false);

            service(provider).ask(USER, new QaAskRequest(null, "问题"));

            String userMessage = provider.seenMessages.get(1).content();
            assertThat(userMessage).startsWith("以下资料片段");
            assertThat(userMessage).contains("资料[1]：分块正文内容。");
            assertThat(userMessage).contains("用户问题：问题");
            assertThat(provider.seenMessages.get(0).role()).isEqualTo("system");
        }
    }

    @Nested
    @DisplayName("入参与归属校验")
    class Guards {

        @Test
        @DisplayName("问题空白报 1001，不落任何行")
        void blankQuestionRejected() {
            assertThatThrownBy(() -> service(new StubProvider(List.of(), false))
                .ask(USER, new QaAskRequest(null, "   ")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.BAD_REQUEST.getCode());
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("追问落在同一会话：引用既有会话且 owner 校验通过后序号续排")
        void followUpReusesSession() {
            stubRetrieval(CHUNK_ID, false);
            UUID sessionId = UUID.randomUUID();
            QaSessionEntity existing = new QaSessionEntity();
            existing.setId(sessionId);
            existing.setUserId(UUID.fromString(USER));
            existing.setTitle("旧会话");
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(existing));
            when(messageRepository.findMaxOrder(sessionId)).thenReturn(4);

            service(new StubProvider(List.of("答"), false)).ask(USER, new QaAskRequest(sessionId.toString(), "追问"));

            assertThat(saved.get(0).getSessionId()).isEqualTo(sessionId);
            assertThat(saved.get(0).getMessageOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("非 owner 会话报 2500，不泄露存在性")
        void foreignSessionReturnsSessionNotFound() {
            UUID sessionId = UUID.randomUUID();
            QaSessionEntity foreign = new QaSessionEntity();
            foreign.setId(sessionId);
            foreign.setUserId(UUID.fromString("99999999-9999-9999-9999-999999999999"));
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> service(new StubProvider(List.of(), false))
                .ask(USER, new QaAskRequest(sessionId.toString(), "问题")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.QA_SESSION_NOT_FOUND.getCode());
        }
    }
}
