package io.annona.modules.evaluation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.stream.TaskStreamPort;
import io.annona.common.usage.UsageContext;
import io.annona.modules.evaluation.entity.InterviewEvaluationEntity;
import io.annona.modules.evaluation.entity.InterviewReportEntity;
import io.annona.modules.evaluation.model.EvaluationSummary;
import io.annona.modules.evaluation.model.GradeBatch;
import io.annona.modules.evaluation.repository.InterviewEvaluationRepository;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import io.annona.shared.ai.StructuredOutputInvoker;
import io.annona.shared.interview.EvalAnswer;
import io.annona.shared.interview.InterviewEvalQueryService;
import io.annona.shared.voice.VoiceEvalQueryService;
import io.annona.shared.question.QuestionGrading;
import io.annona.shared.question.QuestionQueryService;
import io.annona.spi.model.ModelProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 评估消费业务（P1b-06，{@link TaskStreamPort.TaskMessageHandler}）。交卷事件投递后经 Redis
 * Stream 领取：读该会话的已提交作答（shared 只读端口）→ 按题分组逐批走
 * {@link StructuredOutputInvoker}（同步 chat，经 @Primary MeteredModelProvider 自动记 EVALUATION 账）
 * → 二次汇总 → upsert 逐题明细 + 报告置 DONE（interview_report 状态机）。
 *
 * <p>铁律：LLM 调用与 JSON 序列化一律在事务外；每个 DB 写经 {@link TransactionTemplate} 自成一个
 * 短事务（消费线程无现成事务，@Modifying 缺事务必炸）。幂等三重：领取 {@code tryMarkRunning}
 * 条件 UPDATE、逐题 upsert（重投覆盖不双写）、终态 {@code markDone} 钉 RUNNING。执行权失效（他人
 * 已接手 / 已 DONE）一律 ACK 丢弃。降级（畸形输出）落 {@code fallback_used=true} + 保留模型原文。
 */
@Service
public class EvaluationService implements TaskStreamPort.TaskMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /** 本批评分器版本（evaluation-pipeline-adr §决策 3：'v2' 起，'v1' 冻结不动）。 */
    public static final String EVALUATOR_VERSION = "v2";

    /** 消费重试上限（配合端口 RETRY 语义，达限判 FAILED + DEAD；同 questionbank MAX_RETRY）。 */
    static final int MAX_RETRY = 3;

    private final InterviewReportRepository reportRepository;
    private final InterviewEvaluationRepository evaluationRepository;
    private final InterviewEvalQueryService evalQuery;
    private final VoiceEvalQueryService voiceQuery;
    private final QuestionQueryService questionQuery;
    private final ObjectProvider<StructuredOutputInvoker> invoker;
    private final ObjectProvider<ModelProvider> modelProvider;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String gradeSystemPrompt;
    private final String gradeUserTemplate;
    private final String summarySystemPrompt;
    private final String summaryUserTemplate;
    private final String promptHash;

    public EvaluationService(InterviewReportRepository reportRepository,
                             InterviewEvaluationRepository evaluationRepository,
                             InterviewEvalQueryService evalQuery,
                             VoiceEvalQueryService voiceQuery,
                             QuestionQueryService questionQuery,
                             ObjectProvider<StructuredOutputInvoker> invoker,
                             ObjectProvider<ModelProvider> modelProvider,
                             PlatformTransactionManager transactionManager) {
        this.reportRepository = reportRepository;
        this.evaluationRepository = evaluationRepository;
        this.evalQuery = evalQuery;
        this.voiceQuery = voiceQuery;
        this.questionQuery = questionQuery;
        this.invoker = invoker;
        this.modelProvider = modelProvider;
        this.tx = new TransactionTemplate(transactionManager);
        this.gradeSystemPrompt = readPrompt("prompts/evaluation-grade-system.st");
        this.gradeUserTemplate = readPrompt("prompts/evaluation-grade-user.st");
        this.summarySystemPrompt = readPrompt("prompts/evaluation-summary-system.st");
        this.summaryUserTemplate = readPrompt("prompts/evaluation-summary-user.st");
        this.promptHash = computePromptHash();
    }

    /**
     * 评分器身份摘要 = 四段 prompt（评分 system/user 模板、汇总 system/user 模板）按固定顺序
     * 拼接的 SHA-256。取舍：只折静态模板文本，不折渲染后的动态内容（题干/作答逐次不同，
     * 摘要要钉的是“口径”不是“这次评了什么”）。旧写法只折评分 system 段——改汇总模板或
     * user 段的评分指令不会触发趋势断开，与 evaluation-pipeline-adr §决策 6 的意图有缝。
     * 本定义随批 3 收口修复固化（v2 尚未产生任何生产报告，无历史断链；此后改 prompt 不升
     * {@code EVALUATOR_VERSION} 也会被哈希抓到，见 ADR 修订注）。
     */
    private String computePromptHash() {
        return sha256(String.join("\n---\n",
            gradeSystemPrompt, gradeUserTemplate, summarySystemPrompt, summaryUserTemplate));
    }

    @Override
    public TaskStreamPort.Outcome handle(String msgId, Map<String, String> payload, int retryCount) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(payload.get("sessionId"));
        } catch (IllegalArgumentException e) {
            return TaskStreamPort.Outcome.ACK;
        }
        InterviewReportEntity report = reportRepository
            .findBySessionIdAndEvaluatorVersion(sessionId, EVALUATOR_VERSION).orElse(null);
        if (report == null || InterviewReportEntity.STATUS_DONE.equals(report.getStatus())) {
            return TaskStreamPort.Outcome.ACK; // 报告缺失（会话已删）或已终态：ACK 丢弃
        }
        // 领取执行权：PENDING→RUNNING 条件 UPDATE，抢不到 = 他人已接手，ACK
        Integer claimed = tx.execute(s ->
            reportRepository.tryMarkRunning(sessionId, EVALUATOR_VERSION, Instant.now()));
        if (claimed == null || claimed == 0) {
            return TaskStreamPort.Outcome.ACK;
        }
        try {
            doEvaluate(sessionId, report.getUserId(), report.getSessionType());
            return TaskStreamPort.Outcome.ACK;
        } catch (Exception e) {
            log.warn("评估任务 {} 失败（retryCount={}）：{}", sessionId, retryCount, e.getMessage());
            if (retryCount < MAX_RETRY && resetToPending(sessionId)) {
                return TaskStreamPort.Outcome.RETRY;
            }
            tx.executeWithoutResult(s -> reportRepository.markFailed(sessionId, EVALUATOR_VERSION,
                safeMessage(e), Instant.now()));
            return TaskStreamPort.Outcome.DEAD;
        }
    }

    private void doEvaluate(UUID sessionId, UUID userId, String sessionType) {
        // 作答装配按报告类型分流（voice-adr 修订 1）：两种会话同一评分口径与 prompt，
        // 可比性由"同一题库 + 同一 gradingByIds"保证
        List<EvalAnswer> answers = InterviewReportEntity.SESSION_TYPE_VOICE.equals(sessionType)
            ? voiceQuery.submittedAnswers(sessionId, userId)
            : evalQuery.submittedAnswers(sessionId, userId);
        // 执行线程内 bind 归属：逐题 LLM 经 @Primary ModelProvider → MeteredModelProvider 读此上下文记账
        try (UsageContext.Scope ignored = UsageContext.bind(userId.toString(), "EVALUATION",
            sessionId, EVALUATOR_VERSION)) {
            // 按题分组（保会话内题序），一次 LLM 批评估该题的主问题 + 各追问
            Map<UUID, List<EvalAnswer>> byQuestion = new LinkedHashMap<>();
            for (EvalAnswer a : answers) {
                byQuestion.computeIfAbsent(a.questionId(), k -> new java.util.ArrayList<>()).add(a);
            }
            Map<UUID, QuestionGrading> gradingById = byQuestion.keySet().isEmpty() ? Map.of()
                : questionQuery.gradingByIds(byQuestion.keySet()).stream()
                    .collect(Collectors.toMap(QuestionGrading::id, g -> g, (a, b) -> a));

            for (Map.Entry<UUID, List<EvalAnswer>> entry : byQuestion.entrySet()) {
                gradeOneQuestion(sessionId, entry.getKey(), entry.getValue(),
                    gradingById.get(entry.getKey()));
            }

            // 读回刚落库的逐题明细，驱动二次汇总与加权总分（一次查询两用，不再回库）
            var gradeRows = evaluationRepository
                .findBySessionIdAndEvaluatorVersionOrderByQuestionIdAscFollowUpIndexAsc(
                    sessionId, EVALUATOR_VERSION);
            EvaluationSummary summary = summarize(gradeRows);
            short composite = compositeScore(gradeRows, gradingById);
            String model = modelProvider.getIfAvailable() != null ? modelProvider.getObject().name() : null;
            Integer rows = tx.execute(s -> reportRepository.markDone(sessionId, EVALUATOR_VERSION,
                composite, toJson(summary), model, model, promptHash, Instant.now()));
            if (rows == null || rows == 0) {
                // 报告已非 RUNNING（恢复调度回收/他人接手）：分数已算但写不进终态。
                // 不抛——重跑整场评估要再烧一次 LLM，交给恢复调度重新投递更便宜；
                // 告警是此分支唯一的可见性来源，不得静默
                log.warn("报告 {} 置 DONE 影响 0 行（执行权已失效），本轮评分丢弃等重投", sessionId);
            }
        }
    }

    /** 逐题批评估：结构化成功→按 index 映射落分；畸形/缺字段→该题各槽降级并保留原文。 */
    private void gradeOneQuestion(UUID sessionId, UUID questionId, List<EvalAnswer> slots,
                                  QuestionGrading grading) {
        StructuredOutputInvoker bean = invoker.getIfAvailable();
        String userPrompt = buildGradePrompt(slots, grading);
        GradeBatch batch = null;
        String raw = null;
        boolean degraded = false;
        if (bean == null) {
            // 模型未配置：整题降级（无原文），消费不因此崩——交卷已完成，评估尽力而为
            degraded = true;
        } else {
            try {
                StructuredOutputInvoker.StructuredResult<GradeBatch> result =
                    bean.invokeWithRaw(gradeSystemPrompt, userPrompt, GradeBatch.class);
                batch = result.parsed();
                raw = result.raw();
            } catch (StructuredOutputInvoker.StructuredOutputUnparsedException e) {
                degraded = true;
                raw = e.lastRaw();
            }
        }
        for (int i = 0; i < slots.size(); i++) {
            EvalAnswer slot = slots.get(i);
            GradeBatch.QuestionGrade grade = gradeOrNull(batch, i);
            boolean fallback = degraded || grade == null || grade.score() == null
                || grade.score() < 0 || grade.score() > 100;
            Short score = fallback ? null : grade.score().shortValue();
            String feedback = fallback ? "该题评估降级（模型输出无法解析），保留原文待复核" : grade.feedback();
            List<String> strengths = fallback ? List.of() : grade.strengths();
            List<String> improvements = fallback ? List.of() : grade.improvements();
            String rawToStore = fallback ? raw : null;
            tx.executeWithoutResult(s -> evaluationRepository.upsert(UUID.randomUUID(), sessionId,
                questionId, (short) slot.followUpIndex(), EVALUATOR_VERSION, score, feedback,
                toJson(strengths), toJson(improvements), fallback, rawToStore, Instant.now()));
        }
    }

    private EvaluationSummary summarize(List<InterviewEvaluationEntity> gradeRows) {
        StructuredOutputInvoker bean = invoker.getIfAvailable();
        if (bean == null || gradeRows.isEmpty()) {
            return new EvaluationSummary(List.of(), List.of(),
                gradeRows.isEmpty() ? "本次面试无作答" : "");
        }
        String digest = gradeRows.stream()
            .map(g -> (g.isFallbackUsed() || g.getScore() == null ? "[降级]" : "[" + g.getScore() + "分]")
                + " " + nullToEmpty(g.getFeedback()))
            .collect(Collectors.joining("\n"));
        try {
            return bean.invoke(summarySystemPrompt,
                summaryUserTemplate.replace("{digest}", digest), EvaluationSummary.class);
        } catch (RuntimeException e) {
            log.warn("评估二次汇总降级：{}", e.getMessage());
            return new EvaluationSummary(List.of(), List.of(), "整场汇总生成失败，逐题明细见各题反馈");
        }
    }

    /** 难度加权总分：委托 {@link ComparabilityRules}（降级/无分题不计入）；追问沿用主题难度。 */
    short compositeScore(List<InterviewEvaluationEntity> gradeRows,
                         Map<UUID, QuestionGrading> gradingById) {
        List<ComparabilityRules.ScoredAnswer> items = new java.util.ArrayList<>();
        for (var grade : gradeRows) {
            int difficulty = gradingById.containsKey(grade.getQuestionId())
                ? gradingById.get(grade.getQuestionId()).difficulty()
                : ComparabilityRules.DEFAULT_DIFFICULTY;
            if (grade.isFallbackUsed() || grade.getScore() == null) {
                items.add(ComparabilityRules.ScoredAnswer.notGradable(difficulty));
            } else {
                items.add(new ComparabilityRules.ScoredAnswer(grade.getScore(), difficulty, true));
            }
        }
        return (short) ComparabilityRules.weightedTotal(items);
    }

    private boolean resetToPending(UUID sessionId) {
        Integer reset = tx.execute(s -> reportRepository.markRunningBackToPending(
            sessionId, EVALUATOR_VERSION, Instant.now()));
        return reset != null && reset == 1;
    }

    private String buildGradePrompt(List<EvalAnswer> slots, QuestionGrading grading) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < slots.size(); i++) {
            EvalAnswer slot = slots.get(i);
            boolean main = slot.followUpIndex() == 0 || grading == null;
            String stem = main ? (grading == null ? "" : grading.question())
                : followUpStem(grading, slot.followUpIndex());
            String rubric = main
                ? (grading == null ? "" : nullToEmpty(grading.scoringRubric()))
                : followUpRubric(grading, slot.followUpIndex());
            String reference = main
                ? (grading == null ? "" : nullToEmpty(grading.referenceAnswer()))
                : followUpReference(grading, slot.followUpIndex());
            items.append("[槽 ").append(i).append("] 题干：").append(stem)
                .append("\n评分标准：").append(rubric)
                .append("\n参考答案：").append(reference)
                .append("\n候选人作答：").append(nullToEmpty(slot.answerText())).append("\n\n");
        }
        return gradeUserTemplate.replace("{items}", items.toString());
    }

    private static GradeBatch.QuestionGrade gradeOrNull(GradeBatch batch, int index) {
        if (batch == null || batch.grades() == null) {
            return null;
        }
        return batch.grades().stream().filter(g -> g.index() == index).findFirst().orElse(null);
    }

    private static String followUpStem(QuestionGrading g, int idx) {
        List<QuestionGrading.FollowUpGrading> f = g.followUps();
        return idx - 1 < f.size() ? f.get(idx - 1).question() : "";
    }

    private static String followUpRubric(QuestionGrading g, int idx) {
        List<QuestionGrading.FollowUpGrading> f = g.followUps();
        return idx - 1 < f.size() ? nullToEmpty(f.get(idx - 1).scoringRubric()) : "";
    }

    private static String followUpReference(QuestionGrading g, int idx) {
        List<QuestionGrading.FollowUpGrading> f = g.followUps();
        return idx - 1 < f.size() ? nullToEmpty(f.get(idx - 1).referenceAnswer()) : "";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "评估结果序列化失败");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? "评估失败" : (m.length() > 200 ? m.substring(0, 200) : m);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readPrompt(String resource) {
        try (InputStream in = EvaluationService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少评估 prompt 资源：" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
