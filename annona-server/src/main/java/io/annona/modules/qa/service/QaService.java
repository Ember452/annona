package io.annona.modules.qa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.model.ChatMessage;
import io.annona.common.model.ChatStreamListener;
import io.annona.common.model.StreamingChatProvider;
import io.annona.modules.knowledge.dto.KbChunkReference;
import io.annona.modules.knowledge.ops.KnowledgeDocQueryService;
import io.annona.modules.qa.dto.QaAskRequest;
import io.annona.modules.qa.dto.QaCitation;
import io.annona.modules.qa.dto.QaMessageResponse;
import io.annona.modules.qa.dto.QaSessionResponse;
import io.annona.modules.qa.entity.QaMessageEntity;
import io.annona.modules.qa.entity.QaSessionEntity;
import io.annona.modules.qa.mapper.QaMapper;
import io.annona.modules.qa.repository.QaMessageRepository;
import io.annona.modules.qa.repository.QaSessionRepository;
import io.annona.modules.retrieval.dto.RetrievalRequest;
import io.annona.modules.retrieval.service.RetrievalQueryService;
import io.annona.modules.retrieval.dto.RetrievalResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答编排（qa-streaming-adr §决策 3/7/8）：短事务①（会话 + USER 行 + ASSISTANT 空占位）
 * → AI-IO 线程上检索 → 发 sources 事件 → 流式调用（onDelta 转 token 事件）→ 短事务②回填
 * content/citations/completed + done 事件。
 *
 * <p>事务边界：LLM 与检索调用一律在事务外（AGENTS §0 铁律）；本服务跨线程回调，事务用
 * {@link TransactionTemplate}（KnowledgeVectorizeService 先例——@Transactional 自调用
 * 过代理不生效）。检索只读走 RetrievalQueryService、分块正文只读走 KnowledgeDocQueryService
 * （跨模块只读的两条规定路径，qa-streaming-adr §决策 7）。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    /** 会话标题取首问前 20 字（🅢 回退口径）。 */
    private static final int TITLE_LENGTH = 20;

    /** 引用 snippet 截断长度；完整正文回查 kb_doc_chunk，300 字符够面板展示与留痕（V6 列注释）。 */
    private static final int SNIPPET_LENGTH = 300;

    /** SSE 服务端超时（毫秒）：上游挂死时断开连接的兜底——流式无逐 token 超时（provider 注释的残留）。 */
    private static final long EMITTER_TIMEOUT_MS = 120_000L;

    private final QaSessionRepository sessionRepository;
    private final QaMessageRepository messageRepository;
    private final RetrievalQueryService retrievalQueryService;
    private final KnowledgeDocQueryService docQueryService;
    private final Optional<StreamingChatProvider> chatProvider;
    private final Executor aiIoExecutor;
    private final TransactionTemplate tx;
    private final QaMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String systemPrompt;
    private final String userPromptTemplate;

    public QaService(QaSessionRepository sessionRepository, QaMessageRepository messageRepository,
        RetrievalQueryService retrievalQueryService, KnowledgeDocQueryService docQueryService,
        Optional<StreamingChatProvider> chatProvider,
        @Qualifier("aiIoExecutor") Executor aiIoExecutor,
        PlatformTransactionManager transactionManager, QaMapper mapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.retrievalQueryService = retrievalQueryService;
        this.docQueryService = docQueryService;
        this.chatProvider = chatProvider;
        this.aiIoExecutor = aiIoExecutor;
        this.tx = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.systemPrompt = readPrompt("prompts/qa-system.st");
        this.userPromptTemplate = readPrompt("prompts/qa-user.st");
    }

    /**
     * 发起一次流式提问。
     *
     * <p>前置条件：{@code userId} 来自已鉴权 Principal；{@code sessionId} 非 owner 一律
     * 2500（不泄露存在性）。失败语义：问题空白 → 1001；provider 未装配 → 流内 error 事件
     * 2502（SSE 已建立后业务失败走事件，不走 HTTP 状态）。副作用：落 USER 行 + ASSISTANT
     * 占位；异步回填在 AI-IO 线程完成。
     *
     * @return SSE 流：sources → token* → done，失败以 error 事件收尾
     */
    public SseEmitter ask(String userId, QaAskRequest request) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入问题");
        }
        QaMessageEntity placeholder = tx.execute(status -> openPlaceholder(userId, request.sessionId(), question));
        UUID assistantId = placeholder.getId();
        UUID sessionId = placeholder.getSessionId();

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        aiIoExecutor.execute(() -> streamAnswer(userId, sessionId, assistantId, question, emitter));
        return emitter;
    }

    /** 当前用户会话列表（最近活跃倒序）。 */
    public List<QaSessionResponse> sessions(String userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(uuid(userId)).stream()
            .map(mapper::toSessionResponse)
            .toList();
    }

    /** 会话历史（含未完成的 ASSISTANT 部分内容，completed=false 供前端标注）。 */
    public List<QaMessageResponse> messages(String userId, String sessionId) {
        QaSessionEntity session = ownedSession(userId, sessionId);
        return messageRepository.findBySessionIdOrderByMessageOrderAsc(session.getId()).stream()
            .map(mapper::toMessageResponse)
            .toList();
    }

    // ========== 短事务①：会话 + 占位 ==========

    private QaMessageEntity openPlaceholder(String userId, String sessionId, String question) {
        QaSessionEntity session = resolveSession(userId, sessionId, question);
        int nextOrder = messageRepository.findMaxOrder(session.getId()) + 1;

        QaMessageEntity userRow = new QaMessageEntity();
        userRow.setId(UUID.randomUUID());
        userRow.setSessionId(session.getId());
        userRow.setMessageOrder(nextOrder);
        userRow.setType(QaMessageEntity.TYPE_USER);
        userRow.setContent(question);
        userRow.setCompleted(true);
        messageRepository.save(userRow);

        QaMessageEntity assistant = new QaMessageEntity();
        assistant.setId(UUID.randomUUID());
        assistant.setSessionId(session.getId());
        assistant.setMessageOrder(nextOrder + 1);
        assistant.setType(QaMessageEntity.TYPE_ASSISTANT);
        assistant.setCompleted(false);
        messageRepository.save(assistant);
        return assistant;
    }

    private QaSessionEntity resolveSession(String userId, String sessionId, String question) {
        UUID uid = uuid(userId);
        if (sessionId == null || sessionId.isBlank()) {
            QaSessionEntity session = new QaSessionEntity();
            session.setId(UUID.randomUUID());
            session.setUserId(uid);
            session.setTitle(question.length() > TITLE_LENGTH ? question.substring(0, TITLE_LENGTH) : question);
            session.setUpdatedAt(Instant.now());
            return sessionRepository.save(session);
        }
        QaSessionEntity session = ownedSession(userId, sessionId);
        session.setUpdatedAt(Instant.now());
        return sessionRepository.save(session);
    }

    /** 归属校验：非 owner 与不存在（含 id 格式非法）一律 2500，不泄露存在性（identity 现有口径）。 */
    private QaSessionEntity ownedSession(String userId, String sessionId) {
        UUID parsed = sessionId == null ? null : optionalUuid(sessionId).orElse(null);
        if (parsed == null) {
            throw new BusinessException(ErrorCode.QA_SESSION_NOT_FOUND);
        }
        return sessionRepository.findById(parsed)
            .filter(session -> session.getUserId().toString().equals(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.QA_SESSION_NOT_FOUND));
    }

    // ========== AI-IO 线程：检索 → 流式 → 回填 ==========

    private void streamAnswer(String userId, UUID sessionId, UUID assistantId,
        String question, SseEmitter emitter) {
        RetrievalResponse retrieval;
        try {
            retrieval = retrievalQueryService.search(userId, new RetrievalRequest(question, null, "BOTH"));
        } catch (BusinessException e) {
            fail(emitter, assistantId, e);
            return;
        }

        List<KbChunkReference> chunks = docQueryService.chunkReferences(userId,
            retrieval.hits().stream().map(RetrievalResponse.Hit::chunkId).toList());
        Map<String, KbChunkReference> byChunkId = chunks.stream()
            .collect(Collectors.toMap(KbChunkReference::chunkId, Function.identity()));

        // 引用顺序 = 检索 score 序；命中到回查之间被删除的分块静默跳过（缺一条引用优于报错）
        List<QaCitation> citations = new ArrayList<>();
        StringJoiner context = new StringJoiner("\n\n");
        for (RetrievalResponse.Hit hit : retrieval.hits()) {
            KbChunkReference chunk = byChunkId.get(hit.chunkId());
            if (chunk == null) {
                continue;
            }
            citations.add(new QaCitation(hit.docId(), hit.chunkId(), chunk.index(),
                chunk.headingPath(), snippet(chunk.content()), hit.score()));
            context.add("资料[" + citations.size() + "]：" + chunk.content());
        }
        send(emitter, "sources", new SourcesPayload(citations, retrieval.diagnostics().reason().name()));

        if (chatProvider.isEmpty()) {
            fail(emitter, assistantId, new BusinessException(ErrorCode.QA_MODEL_NOT_CONFIGURED));
            return;
        }

        StringBuilder full = new StringBuilder();
        StreamState state = new StreamState();
        String userPrompt = userPromptTemplate
            .replace("{context}", context.toString())
            .replace("{question}", question);
        try {
            chatProvider.get().streamChat(
                List.of(new ChatMessage("system", systemPrompt), new ChatMessage("user", userPrompt)),
                new ChatStreamListener() {
                    @Override
                    public void onDelta(String delta) {
                        full.append(delta);
                        if (!send(emitter, "token", Map.of("delta", delta))) {
                            state.clientGone = true;
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        backfill(assistantId, fullText, citations, !state.clientGone);
                        send(emitter, "done", Map.of("messageId", assistantId.toString()));
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable cause) {
                        log.warn("问答流失败 sessionId={} messageId={}", sessionId, assistantId, cause);
                        backfill(assistantId, full.toString(), null, false);
                        fail(emitter, assistantId, cause);
                    }
                });
        } catch (Exception e) {
            // 提交被拒（AI-IO 池 AbortPolicy 满）等提交期失败：占位行保留为未完成
            log.warn("问答任务提交失败 sessionId={} messageId={}", sessionId, assistantId, e);
            backfill(assistantId, full.toString(), null, false);
            fail(emitter, assistantId, e);
        }
    }

    /** 短事务②：回填回答。completed=false 保留部分内容（断线/中断语义，借 🅖）。 */
    private void backfill(UUID assistantId, String content, List<QaCitation> citations, boolean completed) {
        tx.executeWithoutResult(status ->
            messageRepository.findById(assistantId).ifPresent(row -> {
                row.setContent(content);
                row.setCitations(completed ? citations : null);
                row.setCompleted(completed);
                messageRepository.save(row);
            }));
    }

    private void fail(SseEmitter emitter, UUID assistantId, Throwable cause) {
        int code = cause instanceof BusinessException business
            ? business.getCode() : ErrorCode.INTERNAL_ERROR.getCode();
        String message = cause.getMessage() == null || cause.getMessage().isBlank()
            ? ErrorCode.INTERNAL_ERROR.getMessage() : cause.getMessage();
        send(emitter, "error", new ErrorPayload(code, message));
        emitter.complete();
    }

    /** @return true = 发送成功；false = 客户端已断开（计入回填判定，不中断流）。 */
    private boolean send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(payload)));
            return true;
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端断开）event={}", event);
            return false;
        }
    }

    // ========== 流状态（onDelta/onComplete 与 send 之间传递 clientGone） ==========

    private static final class StreamState {
        private boolean clientGone;
    }

    // ========== SSE 载荷（JSON 信封，qa-streaming-adr §决策 3） ==========

    private record SourcesPayload(List<QaCitation> citations, String reason) {
    }

    private record ErrorPayload(int code, String message) {
    }

    // ========== 工具 ==========

    private static String snippet(String content) {
        return content.length() > SNIPPET_LENGTH ? content.substring(0, SNIPPET_LENGTH) : content;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static Optional<UUID> optionalUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String readPrompt(String path) {
        try (InputStream in = QaService.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("prompt 资源缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompt 读取失败: " + path, e);
        }
    }
}
