package io.annona.modules.plan.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.support.ContentHashes;
import io.annona.common.usage.UsageContext;
import io.annona.modules.plan.dto.PlanTaskResponse;
import io.annona.modules.plan.dto.SplitResponse;
import io.annona.modules.plan.entity.PlanEntity;
import io.annona.modules.plan.entity.PlanTaskEntity;
import io.annona.modules.plan.mapper.PlanMapper;
import io.annona.modules.plan.repository.PlanRepository;
import io.annona.modules.plan.repository.PlanTaskRepository;
import io.annona.shared.ai.StructuredOutputInvoker;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MD → 任务拆分（plan-module-adr §决策 2）：指纹短路 → 结构化输出（PLAN scene 计量）→
 * 归一化 → reconcile 落库。同步执行（上游的 splitting 状态机被 ADR 否决）。
 *
 * <p>事务边界（AGENTS §0.3 硬规则，KnowledgeVectorizeService 同款三段式）：只读取数
 * （autocommit）→ 事务外调模型 → {@link TransactionTemplate} 短事务写 reconcile 结果——
 * LLM 重试期间不得持有 DB 连接/事务。{@code split()} 带 {@code @Transactional} 即违反本约束
 * （TransactionBoundaryTest 机检）。
 *
 * <p>失败语义：模型不可用/解析失败 → 3302（StructuredOutputInvoker 的重试耗尽向上冒泡）；
 * 计划不存在或非本人 → 3300（取数即拒）。
 */
@Service
public class PlanSplitService {

    private static final int MAX_TASKS = 30;
    private static final int MAX_TITLE = 160;
    private static final int MAX_DESCRIPTION = 600;
    private static final int MAX_TARGET_MINUTES = 600;
    private static final int MIN_TARGET_MINUTES = 5;
    private static final int DEFAULT_TARGET_MINUTES = 25;
    /** 文档进 prompt 的截断长度：8k 字符 ≈ 计划全文的合理上界，防 token 失控。 */
    private static final int MAX_DOCUMENT_IN_PROMPT = 8000;

    private static final String SYSTEM_PROMPT = """
        你是学习计划拆解助手。把用户的学习计划文档拆解为可执行的任务清单。
        只输出 JSON：{"tasks":[{"title":"...","description":"...","category":"study|project|review|exercise","priority":"high|normal|low","targetMinutes":25}]}
        要求：title 是祈使句短句（≤160 字符）；description 补充做法与验收（≤600 字符，可空串）；
        category 从 study/project/review/exercise 四选一；priority 从 high/normal/low 三选一；
        targetMinutes 是预计专注分钟数（5..600 的整数）。任务数量不超过 30 条，按文档顺序排列。
        """;

    private final PlanRepository planRepository;
    private final PlanTaskRepository taskRepository;
    private final ObjectProvider<StructuredOutputInvoker> invoker;
    private final PlanMapper mapper;
    private final TransactionTemplate tx;

    public PlanSplitService(PlanRepository planRepository, PlanTaskRepository taskRepository,
                            ObjectProvider<StructuredOutputInvoker> invoker, PlanMapper mapper,
                            PlatformTransactionManager transactionManager) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.invoker = invoker;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 拆分/重拆计划文档为任务清单。
     *
     * <p>前置：计划存在且属主匹配（否则 3300）；模型已装配（否则 3302）。
     * <p>事务：取数与模型调用不在事务内；reconcile 写入为单个短事务（含指纹回写）。
     * <p>副作用：调模型消耗 token（PLAN scene 记账）；新任务 source=AI。
     */
    public SplitResponse split(String userId, UUID planId) {
        PlanEntity plan = ownedPlan(userId, planId);
        String hash = ContentHashes.sha256Hex(plan.getDocument() == null ? "" : plan.getDocument());
        List<PlanTaskEntity> existing = taskRepository.findByPlanIdOrderByCreatedAtAsc(planId);

        if (hash.equals(plan.getSourceHash())) {
            // 指纹未变：短路返回，不调模型不花 token（ADR §决策 2）
            return new SplitResponse(existing.stream().map(mapper::toTaskResponse).toList(), false);
        }

        if (invoker.getIfAvailable() == null) {
            throw new BusinessException(ErrorCode.PLAN_SPLIT_UNAVAILABLE);
        }
        List<TaskReconciler.Incoming> incoming = invokeModel(userId, planId, plan.getDocument());

        TaskReconciler.Outcome outcome = TaskReconciler.reconcile(existing, incoming);
        // 受管性不在跨事务边界处指望游离引用：写入短事务内重读指纹所需实体（applyOutcome 自带 plan）
        tx.executeWithoutResult(status -> applyOutcome(plan, outcome));

        List<PlanTaskEntity> refreshed = taskRepository.findByPlanIdOrderByCreatedAtAsc(planId);
        return new SplitResponse(refreshed.stream().map(mapper::toTaskResponse).toList(), true);
    }

    private List<TaskReconciler.Incoming> invokeModel(String userId, UUID planId, String document) {
        String truncated = document.length() > MAX_DOCUMENT_IN_PROMPT
            ? document.substring(0, MAX_DOCUMENT_IN_PROMPT) : document;
        String userPrompt = "学习计划文档：\n\n" + truncated + "\n\n请按系统指令拆解为任务清单。";
        // PLAN scene 计量：MeteredModelProvider 读 bind 做配额与记账（调用点零额外代码）
        try (UsageContext.Scope ignored = UsageContext.bind(userId, "PLAN", planId, null)) {
            SplitResult result = invoker.getObject().invoke(SYSTEM_PROMPT, userPrompt, SplitResult.class);
            return result.tasks() == null ? List.of() : result.tasks().stream()
                .map(PlanSplitService::normalize)
                .toList();
        }
    }

    private void applyOutcome(PlanEntity plan, TaskReconciler.Outcome outcome) {
        for (PlanTaskEntity fresh : outcome.create()) {
            // 内容字段已由 reconcile 预填；服务层只补归属（ADR §决策 2）
            fresh.setPlanId(plan.getId());
            fresh.setUserId(plan.getUserId());
            fresh.setDirectionId(plan.getDirectionId());
            taskRepository.save(fresh);
        }
        for (TaskReconciler.UpdatePair pair : outcome.update()) {
            PlanTaskEntity task = pair.task();
            TaskReconciler.Incoming incoming = pair.incoming();
            task.setTitle(incoming.title());
            task.setDescription(incoming.description());
            task.setCategory(incoming.category());
            task.setPriority(incoming.priority());
            task.setTargetMinutes(incoming.targetMinutes());
            // status / progressMinutes / directionId / source 保持——ADR §决策 2
            taskRepository.save(task);
        }
        outcome.delete().forEach(taskRepository::delete);
        plan.setSourceHash(ContentHashes.sha256Hex(plan.getDocument() == null ? "" : plan.getDocument()));
        planRepository.save(plan);
    }

    private PlanEntity ownedPlan(String userId, UUID planId) {
        return planRepository.findByIdAndUserId(planId, UUID.fromString(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    private static List<TaskReconciler.Incoming> normalize(List<SplitTask> tasks) {
        List<TaskReconciler.Incoming> normalized = new ArrayList<>();
        for (SplitTask task : tasks) {
            if (normalized.size() >= MAX_TASKS) break;
            if (task.title() == null || task.title().isBlank()) continue;
            normalized.add(normalize(task));
        }
        return normalized;
    }

    /** 非法值兜底而非报错：模型输出不可控，归一化后全部可用（ADR §决策 2）。 */
    static TaskReconciler.Incoming normalize(SplitTask task) {
        String title = truncate(task.title().trim(), MAX_TITLE);
        String description = task.description() == null ? "" : truncate(task.description().trim(), MAX_DESCRIPTION);
        String category = task.category() != null && PlanTaskEntity.CATEGORIES.contains(task.category())
            ? task.category() : "study";
        String priority = task.priority() != null && PlanTaskEntity.PRIORITIES.contains(task.priority())
            ? task.priority() : "normal";
        int target = task.targetMinutes() == null ? DEFAULT_TARGET_MINUTES
            : Math.clamp(task.targetMinutes(), MIN_TARGET_MINUTES, MAX_TARGET_MINUTES);
        return new TaskReconciler.Incoming(title, description, category, priority, target);
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** 结构化输出目标形状（与上游 plan-split 的 JSON schema 对齐）。 */
    public record SplitResult(List<SplitTask> tasks) {
    }

    public record SplitTask(String title, String description, String category, String priority,
                            Integer targetMinutes) {
    }
}
