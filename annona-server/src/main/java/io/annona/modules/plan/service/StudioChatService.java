package io.annona.modules.plan.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.model.ChatMessage;
import io.annona.common.model.ChatStreamListener;
import io.annona.common.model.StreamingChatProvider;
import io.annona.common.usage.UsageContext;
import io.annona.common.usage.UsageLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.modules.plan.dto.StudioChatRequest;
import io.annona.modules.plan.entity.PlanEntity;
import io.annona.modules.plan.repository.PlanRepository;
import io.annona.spi.dto.UsageInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 工作室 AI 对话（plan-module-adr §决策 4）：SSE 三事件 token/done/error，上下文 =
 * 计划文档（8k 截断）+ 用户消息（可带选中片段）。不落库——对话历史在前端内存（v1 口径）。
 *
 * <p>线程与事务约束与 QaService 同源：LLM 调用在 ai-io 池执行线程上，UsageContext 必须
 * 在该线程 bind（ThreadLocal 不跨线程）；PLAN scene 记账在终态回调显式走 UsageLedger
 * （流式链不经 MeteredModelProvider 装饰器）。
 */
@Service
public class StudioChatService {

    private static final Logger log = LoggerFactory.getLogger(StudioChatService.class);

    private static final long EMITTER_TIMEOUT_FALLBACK_MS = 120_000L;
    private static final int MAX_DOCUMENT_IN_PROMPT = 8000;
    private static final int MAX_SELECTION = 600;
    private static final int MAX_MESSAGE = 4000;

    private static final String SYSTEM_PROMPT = """
        你是学习计划工作台的写作助手，基于给定的计划文档回答问题、改写段落、补充细节。
        回答使用简体中文，Markdown 格式；引用文档内容时保持原文事实，不要编造计划里不存在的内容。
        """;

    private final PlanRepository planRepository;
    private final Optional<StreamingChatProvider> chatProvider;
    private final Executor aiIoExecutor;
    private final UsageLedger usageLedger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StudioChatService(PlanRepository planRepository,
                             Optional<StreamingChatProvider> chatProvider,
                             @Qualifier("aiIoExecutor") Executor aiIoExecutor,
                             UsageLedger usageLedger) {
        this.planRepository = planRepository;
        this.chatProvider = chatProvider;
        this.aiIoExecutor = aiIoExecutor;
        this.usageLedger = usageLedger;
    }

    /**
     * 发起工作室对话流。
     *
     * <p>失败语义：非 owner → 3300；消息空白 → 1001；provider 未装配/池饱和 → 1100
     * （SSE 未建立，走 HTTP 业务错误）；SSE 建立后失败走 error 事件。
     */
    public SseEmitter chat(String userId, UUID planId, StudioChatRequest request) {
        PlanEntity plan = planRepository.findByIdAndUserId(planId, UUID.fromString(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入消息");
        }
        if (message.length() > MAX_MESSAGE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "消息过长（上限 " + MAX_MESSAGE + " 字符）");
        }

        SseEmitter emitter = new SseEmitter(
            2 * chatProvider.map(StreamingChatProvider::streamTimeoutMillis)
                .filter(ms -> ms > 0).orElse(EMITTER_TIMEOUT_FALLBACK_MS / 2));
        try {
            aiIoExecutor.execute(() -> streamAnswer(userId, plan, request, emitter));
        } catch (RejectedExecutionException e) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "当前助手繁忙，请稍后重试");
        }
        return emitter;
    }

    private void streamAnswer(String userId, PlanEntity plan, StudioChatRequest request,
                              SseEmitter emitter) {
        try (UsageContext.Scope ignored = UsageContext.bind(userId, "PLAN", plan.getId(), null)) {
            doStreamAnswer(userId, plan, request, emitter);
        }
    }

    private void doStreamAnswer(String userId, PlanEntity plan, StudioChatRequest request,
                                SseEmitter emitter) {
        if (chatProvider.isEmpty()) {
            fail(emitter, new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "模型未配置"));
            return;
        }
        String document = plan.getDocument() == null ? "" : plan.getDocument();
        String doc = document.length() > MAX_DOCUMENT_IN_PROMPT
            ? document.substring(0, MAX_DOCUMENT_IN_PROMPT) : document;
        // 选中片段：作为独立引用语境附在消息前（>600 字符截断，上游同口径）
        String selection = request.selection() == null ? ""
            : request.selection().length() > MAX_SELECTION
            ? request.selection().substring(0, MAX_SELECTION) : request.selection();
        String userPrompt = (selection.isEmpty() ? "" : "用户选中的片段：「" + selection + "」\n\n")
            + "计划文档：\n" + doc + "\n\n用户消息：" + request.message().trim();

        StringBuilder full = new StringBuilder();
        try {
            chatProvider.get().streamChat(
                List.of(new ChatMessage("system", SYSTEM_PROMPT), new ChatMessage("user", userPrompt)),
                new ChatStreamListener() {
                    @Override
                    public void onDelta(String delta) {
                        full.append(delta);
                        send(emitter, "token", Map.of("delta", delta));
                    }

                    @Override
                    public void onComplete(String fullText, UsageInfo usage) {
                        if (usage != null && usage.totalTokens() > 0) {
                            StreamingChatProvider provider = chatProvider.orElseThrow();
                            usageLedger.record(new UsageLedger.UsageEntry(UUID.fromString(userId),
                                "PLAN", plan.getId(), provider.channel(), provider.name(), "chat",
                                usage.promptTokens(), usage.completionTokens(), null, null));
                        }
                        send(emitter, "done", Map.of("planId", plan.getId().toString()));
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable cause) {
                        log.warn("工作室对话流失败 planId={}", plan.getId(), cause);
                        fail(emitter, cause);
                    }
                });
        } catch (Exception e) {
            log.warn("工作室对话调用失败 planId={}", plan.getId(), e);
            fail(emitter, e);
        }
    }

    private void fail(SseEmitter emitter, Throwable cause) {
        int code = cause instanceof BusinessException business
            ? business.getCode() : ErrorCode.INTERNAL_ERROR.getCode();
        String message = cause.getMessage() == null || cause.getMessage().isBlank()
            ? ErrorCode.INTERNAL_ERROR.getMessage() : cause.getMessage();
        send(emitter, "error", new ErrorPayload(code, message));
        emitter.complete();
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端断开）event={}", event);
        }
    }

    private record ErrorPayload(int code, String message) {
    }
}
