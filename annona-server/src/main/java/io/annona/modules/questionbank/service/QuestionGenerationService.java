package io.annona.modules.questionbank.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.interview.skill.model.SkillDefinition;
import io.annona.modules.interview.skill.service.SkillQueryService;
import io.annona.modules.knowledge.ops.KnowledgeDocQueryService;
import io.annona.modules.questionbank.entity.QbGenerationTaskEntity;
import io.annona.modules.questionbank.entity.QbQuestionEntity;
import io.annona.modules.questionbank.model.QbFollowUp;
import io.annona.modules.questionbank.model.QbSourceRef;
import io.annona.modules.questionbank.model.QuestionGenConfig;
import io.annona.modules.questionbank.repository.QbGenerationTaskRepository;
import io.annona.modules.questionbank.repository.QbQuestionRepository;
import io.annona.shared.ai.StructuredOutputInvoker;
import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.progress.ProgressEvent;
import io.annona.shared.progress.SseProgressHub;
import io.annona.spi.dto.RetrievalHit;
import io.annona.spi.dto.RetrievalQuery;
import io.annona.spi.retrieval.Retriever;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 出题执行器（P1b-02 核心）：从方向绑定文档的检索分块生成"主问题 + 参考答案 + 关键点 +
 * 评分标准 + 追问"，按 (user, direction) 替换 DRAFT 保留 ACTIVE（ADR §决策 5）。
 * 同时实现 {@link TaskStreamPort.TaskMessageHandler}——重试决策（RETRY/DEAD）在这里，
 * 端口只负责重投递。
 *
 * <p>事务纪律：LLM 与检索调用在事务外（本类不标 @Transactional），草稿替换走
 * TransactionTemplate 最小事务（qa 先例：自调用过不了代理，直接用模板）。
 * 上下文组装借 🅖 的"多固定查询 + 去重 + 截断"口径；persona 注入是 annona 的
 * Skill 驱动出题（设计文档 §91），上游没有。
 */
@Service
public class QuestionGenerationService implements TaskStreamPort.TaskMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationService.class);

    /** 重试上限（与端口 RETRY 语义配合；耗尽判 DEAD）。 */
    static final int MAX_RETRY = 3;
    /** 固定检索查询组（借 🅖：核心概念/关键流程/规则约束/典型案例，各取 top-4）。 */
    private static final String[] CONTEXT_QUERY_SUFFIXES = {"核心概念", "关键流程", "规则约束", "典型案例"};
    private static final int PER_QUERY_TOP_K = 4;
    private static final int MAX_CONTEXT_CHUNKS = 12;
    private static final int MAX_CONTEXT_CHARS = 5000;
    private static final int EXISTING_FEED_LIMIT = 20;

    private final QbGenerationTaskRepository taskRepository;
    private final QbQuestionRepository questionRepository;
    private final QuestionGenStateService stateService;
    private final DirectionQueryService directionQuery;
    private final SkillQueryService skillQuery;
    private final KnowledgeDocQueryService knowledgeDocQuery;
    private final Retriever retriever;
    private final ObjectProvider<StructuredOutputInvoker> invoker;
    private final SseProgressHub progressHub;
    private final TransactionTemplate txTemplate;
    private final String systemPrompt;
    private final String userPromptTemplate;

    public QuestionGenerationService(QbGenerationTaskRepository taskRepository,
                                     QbQuestionRepository questionRepository,
                                     QuestionGenStateService stateService,
                                     DirectionQueryService directionQuery,
                                     SkillQueryService skillQuery,
                                     KnowledgeDocQueryService knowledgeDocQuery,
                                     Retriever retriever,
                                     ObjectProvider<StructuredOutputInvoker> invoker,
                                     SseProgressHub progressHub,
                                     PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.questionRepository = questionRepository;
        this.stateService = stateService;
        this.directionQuery = directionQuery;
        this.skillQuery = skillQuery;
        this.knowledgeDocQuery = knowledgeDocQuery;
        this.retriever = retriever;
        this.invoker = invoker;
        this.progressHub = progressHub;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.systemPrompt = readPrompt("prompts/question-generation-system.st");
        this.userPromptTemplate = readPrompt("prompts/question-generation-user.st");
    }

    @Override
    public TaskStreamPort.Outcome handle(String msgId, Map<String, String> payload, int retryCount) {
        UUID taskId = UUID.fromString(payload.get("taskId"));
        // 原子领取失败 = 已被其他实例领取或已终态：安静放弃（ACK），不是错误
        if (!stateService.tryMarkProcessing(taskId)) {
            return TaskStreamPort.Outcome.ACK;
        }
        try {
            run(taskId);
            return TaskStreamPort.Outcome.ACK;
        } catch (Exception e) {
            log.warn("出题任务 {} 失败（retryCount={}）：{}", taskId, retryCount, e.getMessage());
            if (retryCount < MAX_RETRY && stateService.resetForRetry(taskId)) {
                return TaskStreamPort.Outcome.RETRY;
            }
            stateService.markFailed(taskId, safeMessage(e));
            publishFailed(taskId);
            return TaskStreamPort.Outcome.DEAD;
        }
    }

    /** 领取后的完整执行：取任务 → 校验方向与文档 → 组上下文 → LLM → 落库 → 完成落账。 */
    void run(UUID taskId) {
        QbGenerationTaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("出题任务 {} 不存在（可能已被清理），跳过", taskId);
            return;
        }
        QuestionGenConfig config = task.getConfig();
        publish(task.getDirectionId(), "PROCESSING", "正在出题", 0, config.questionCount(), "");

        DirectionResponse direction = directionQuery
            .findVisible(task.getUserId().toString(), task.getDirectionId().toString())
            .orElseThrow(() -> new BusinessException(ErrorCode.DIRECTION_NOT_FOUND));
        String persona = skillQuery.find(direction.key())
            .map(SkillDefinition::persona).orElse("");
        ContextResult context = buildContext(task.getUserId().toString(), direction);
        List<String> existing = questionRepository.findRecentQuestions(
            task.getUserId(), task.getDirectionId(), PageRequest.of(0, EXISTING_FEED_LIMIT));

        String userPrompt = userPromptTemplate
            .replace("{directionName}", direction.name())
            .replace("{difficulty}", String.valueOf(config.difficulty()))
            .replace("{questionCount}", String.valueOf(config.questionCount()))
            .replace("{followUpCount}", String.valueOf(config.followUpCount()))
            .replace("{skillSection}", persona.isBlank() ? "（该方向未配置 SKILL，按资料通用出题）"
                : persona)
            .replace("{contextSection}", context.text())
            .replace("{existingSection}", existing.isEmpty() ? "（暂无）"
                : String.join("\n- ", existing));

        List<ParsedQuestion> parsed = invoker.getObject()
            .invoke(systemPrompt, userPrompt, QuestionListPayload.class).questions().stream()
            .map(q -> new ParsedQuestion(q.question(), q.topicSummary(), q.referenceAnswer(),
                orEmpty(q.keyPoints()), q.scoringRubric(), orFollowUps(q.followUps())))
            .toList();

        int saved = persistDrafts(task, config, parsed, context.sources());
        int skipped = parsed.size() - saved;
        String message = skipped > 0
            ? "已生成 %d 题，跳过 %d 道重复或无效题".formatted(saved, skipped)
            : "已生成 %d 题".formatted(saved);
        stateService.markCompleted(taskId, saved, skipped, message);
        publish(task.getDirectionId(), "COMPLETED", "出题完成", saved, config.questionCount(), message);
    }

    /** 检索上下文：固定查询组（借 🅖）× 文档域限定（SPI kbDocIds），去重截断后取分块全文。 */
    private ContextResult buildContext(String userId, DirectionResponse direction) {
        if (direction.kbDocId() == null || direction.kbDocId().isBlank()) {
            throw new BusinessException(ErrorCode.QB_DIRECTION_KB_NOT_READY, "该方向未绑定知识库文档");
        }
        List<String> docScope = List.of(direction.kbDocId());
        Map<String, RetrievalHit> merged = new LinkedHashMap<>();
        for (String suffix : CONTEXT_QUERY_SUFFIXES) {
            List<RetrievalHit> hits = retriever.retrieve(new RetrievalQuery(
                direction.name() + " " + suffix, PER_QUERY_TOP_K, userId, docScope, null));
            for (RetrievalHit hit : hits) {
                merged.putIfAbsent(hit.chunkId(), hit);
            }
        }
        List<RetrievalHit> picked = List.copyOf(merged.values())
            .stream().limit(MAX_CONTEXT_CHUNKS).toList();
        if (picked.isEmpty()) {
            throw new BusinessException(ErrorCode.QB_DIRECTION_KB_NOT_READY,
                "知识库检索为空，无法出题（文档内容不足以支撑出题）");
        }
        List<String> chunkIds = picked.stream().map(RetrievalHit::chunkId).toList();
        Map<String, String> contents = new LinkedHashMap<>();
        Map<String, String> headings = new LinkedHashMap<>();
        knowledgeDocQuery.chunkReferences(userId, chunkIds).forEach(ref -> {
            contents.put(ref.chunkId(), ref.content());
            headings.put(ref.chunkId(), ref.headingPath());
        });
        StringBuilder context = new StringBuilder();
        List<QbSourceRef> sources = new ArrayList<>();
        for (RetrievalHit hit : picked) {
            String content = contents.getOrDefault(hit.chunkId(), hit.snippet());
            sources.add(new QbSourceRef(UUID.fromString(hit.docId()), UUID.fromString(hit.chunkId()),
                headings.get(hit.chunkId())));
            if (context.length() + content.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            context.append(context.isEmpty() ? "" : "\n\n---\n\n").append(content);
        }
        if (context.isEmpty()) {
            throw new BusinessException(ErrorCode.QB_DIRECTION_KB_NOT_READY, "知识库检索为空，无法出题");
        }
        return new ContextResult(context.toString(), List.copyOf(sources));
    }

    /** 草稿替换落库（最小事务）：删旧 DRAFT + 写新 DRAFT；去重/截断规则见类注释与 ADR。 */
    private int persistDrafts(QbGenerationTaskEntity task, QuestionGenConfig config,
                              List<ParsedQuestion> parsed, List<QbSourceRef> sources) {
        Map<String, String> existing = new LinkedHashMap<>();
        questionRepository.findRecentQuestions(task.getUserId(), task.getDirectionId(),
                PageRequest.of(0, EXISTING_FEED_LIMIT))
            .forEach(text -> existing.put(normalize(text), text));
        List<QbQuestionEntity> drafts = new ArrayList<>();
        for (ParsedQuestion q : parsed) {
            String text = q.question() == null ? "" : q.question().strip();
            if (text.isEmpty() || existing.containsKey(normalize(text))) {
                continue;
            }
            existing.put(normalize(text), text);
            QbQuestionEntity entity = new QbQuestionEntity();
            entity.setId(UUID.randomUUID());
            entity.setUserId(task.getUserId());
            entity.setDirectionId(task.getDirectionId());
            entity.setQuestion(text);
            entity.setTopicSummary(q.topicSummary());
            entity.setReferenceAnswer(q.referenceAnswer());
            entity.setKeyPoints(q.keyPoints());
            entity.setScoringRubric(q.scoringRubric());
            entity.setDifficulty((short) config.difficulty());
            entity.setFollowUps(followUps(q.followUps(), config.followUpCount()));
            entity.setSources(sources);
            entity.setStatus(QbQuestionEntity.STATUS_DRAFT);
            entity.setUpdatedAt(Instant.now());
            drafts.add(entity);
        }
        txTemplate.executeWithoutResult(status -> {
            questionRepository.deleteDrafts(task.getUserId(), task.getDirectionId());
            questionRepository.saveAll(drafts);
        });
        return drafts.size();
    }

    /** 追问裁剪：空题干剔除 + 截断到目标数（模型多给不要、少给如实呈现——上游口径）。 */
    private List<QbFollowUp> followUps(List<QbFollowUp> followUps, int target) {
        if (followUps == null) {
            return List.of();
        }
        return followUps.stream()
            .filter(f -> f.question() != null && !f.question().isBlank())
            .limit(Math.max(target, 0))
            .toList();
    }

    /** NFC 无关紧要——中文题干的判重口径：去空白后整串比较（借 🅖 的批内去重思路，从简）。 */
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private void publish(UUID directionId, String status, String stage, int processed, int total,
                         String message) {
        progressHub.publish(directionId, new ProgressEvent(status, stage, processed, total, message));
    }

    private void publishFailed(UUID taskId) {
        taskRepository.findById(taskId).ifPresent(task -> publish(task.getDirectionId(), "FAILED",
            "出题失败", 0, task.getConfig().questionCount(), "出题失败，请稍后重试"));
    }

    private static String safeMessage(Exception e) {
        return e instanceof BusinessException be ? be.getMessage()
            : "出题失败，请稍后重试";
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<QbFollowUp> orFollowUps(List<QbFollowUp> list) {
        return list == null ? List.of() : list;
    }

    private String readPrompt(String location) {
        try (var in = getClass().getClassLoader().getResourceAsStream(location)) {
            if (in == null) {
                throw new IllegalStateException("提示词文件缺失：" + location);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("提示词文件不可读：" + location, e);
        }
    }

    /** LLM 返回的题目（解析失败由 Invoker 重试兜底；本记录即输出契约）。 */
    record QuestionListPayload(List<ParsedQuestion> questions) {
    }

    record ParsedQuestion(String question, String topicSummary, String referenceAnswer,
                          List<String> keyPoints, String scoringRubric, List<QbFollowUp> followUps) {
    }

    /** 检索上下文与它的来源快照（sources 列的输入）。 */
    private record ContextResult(String text, List<QbSourceRef> sources) {
    }
}
