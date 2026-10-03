package io.annona.modules.plan.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.plan.dto.CreatePlanRequest;
import io.annona.modules.plan.dto.CreateTaskRequest;
import io.annona.modules.plan.dto.PatchTaskRequest;
import io.annona.modules.plan.dto.PlanDetailResponse;
import io.annona.modules.plan.dto.PlanSummaryResponse;
import io.annona.modules.plan.dto.PlanTaskResponse;
import io.annona.modules.plan.dto.UpdatePlanRequest;
import io.annona.modules.plan.entity.PlanEntity;
import io.annona.modules.plan.entity.PlanTaskEntity;
import io.annona.modules.plan.mapper.PlanMapper;
import io.annona.modules.plan.repository.PlanRepository;
import io.annona.modules.plan.repository.PlanTaskRepository;
import io.annona.shared.direction.service.DirectionQueryService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计划 CRUD、今日待办与任务手动勾选（plan-module-adr）。
 * 进度摘要（done/total）现算不落库；今日待办按 priority 手工排序（DB 字母序与
 * high>normal>low 不一致，三值排序放内存）。
 */
@Service
public class PlanService {

    private static final int MAX_DOCUMENT = 200_000;
    /** 任务标题上限（与 AI 拆分的 MAX_TITLE 同值，列宽 VARCHAR(160)）。 */
    private static final int MAX_TASK_TITLE = 160;

    private final PlanRepository planRepository;
    private final PlanTaskRepository taskRepository;
    private final DirectionQueryService directions;
    private final PlanMapper mapper;

    public PlanService(PlanRepository planRepository, PlanTaskRepository taskRepository,
                       DirectionQueryService directions, PlanMapper mapper) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.directions = directions;
        this.mapper = mapper;
    }

    @Transactional
    public PlanDetailResponse create(String userId, CreatePlanRequest request) {
        String title = requireTitle(request.title());
        if (request.document() != null && request.document().length() > MAX_DOCUMENT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "计划文档过长（上限 " + MAX_DOCUMENT + " 字符）");
        }
        UUID directionId = requireVisibleDirection(userId, request.directionId());

        PlanEntity plan = new PlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setUserId(UUID.fromString(userId));
        plan.setDirectionId(directionId);
        plan.setTitle(title);
        plan.setDocument(request.document() == null ? "" : request.document());
        planRepository.save(plan);
        return detail(userId, plan.getId());
    }

    /** 列表（附任务进度摘要）：进度经单次 GROUP BY 批量取回，不逐计划 count。 */
    @Transactional(readOnly = true)
    public List<PlanSummaryResponse> list(String userId) {
        UUID uid = UUID.fromString(userId);
        Map<UUID, long[]> summaries = new HashMap<>();
        for (Object[] row : taskRepository.countSummaryByUser(uid)) {
            summaries.put((UUID) row[0], new long[]{((Number) row[1]).longValue(),
                ((Number) row[2]).longValue()});
        }
        return planRepository.findByUserIdOrderByUpdatedAtDesc(uid).stream()
            .map(plan -> {
                long[] counts = summaries.getOrDefault(plan.getId(), new long[2]);
                return new PlanSummaryResponse(
                    plan.getId().toString(), plan.getTitle(),
                    plan.getDirectionId() == null ? null : plan.getDirectionId().toString(),
                    counts[0], counts[1], plan.getUpdatedAt());
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public PlanDetailResponse detail(String userId, UUID planId) {
        PlanEntity plan = ownedPlan(userId, planId);
        List<PlanTaskEntity> tasks = taskRepository.findByPlanIdOrderByCreatedAtAsc(planId);
        // stale = 指纹与当前文档不一致；从未拆过且无任务时也提示重拆
        boolean stale = plan.getSourceHash() == null && tasks.isEmpty();
        return new PlanDetailResponse(plan.getId().toString(), plan.getTitle(),
            plan.getDirectionId() == null ? null : plan.getDirectionId().toString(),
            plan.getDocument(), stale,
            tasks.stream().map(mapper::toTaskResponse).toList(), plan.getUpdatedAt());
    }

    @Transactional
    public PlanDetailResponse update(String userId, UUID planId, UpdatePlanRequest request) {
        PlanEntity plan = ownedPlan(userId, planId);
        if (request.title() != null) {
            plan.setTitle(requireTitle(request.title()));
        }
        if (request.document() != null) {
            if (request.document().length() > MAX_DOCUMENT) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "计划文档过长（上限 " + MAX_DOCUMENT + " 字符）");
            }
            plan.setDocument(request.document());
            // 文档变了：旧指纹作废，下次 split 重新计算（ADR §决策 2）
            plan.setSourceHash(null);
        }
        planRepository.save(plan);
        return detail(userId, planId);
    }

    @Transactional
    public void delete(String userId, UUID planId) {
        PlanEntity plan = ownedPlan(userId, planId);
        planRepository.delete(plan);
    }

    @Transactional
    public PlanTaskResponse addTask(String userId, UUID planId, CreateTaskRequest request) {
        PlanEntity plan = ownedPlan(userId, planId);
        if (request.title() == null || request.title().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务标题不能为空");
        }
        String title = request.title().trim();
        if (title.length() > MAX_TASK_TITLE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务标题不得超过 " + MAX_TASK_TITLE + " 字符");
        }
        // 联动方向：显式给值校验可见性；缺省继承计划归属；显式 null = 不联动——
        // JSON 里区分"没传"与"传 null"做不到（Jackson 均为 null），故缺省即继承
        UUID directionId = request.directionId() != null
            ? requireVisibleDirection(userId, request.directionId())
            : plan.getDirectionId();
        int target = request.targetMinutes() == null ? 25
            : Math.clamp(request.targetMinutes(), 5, 600);

        PlanTaskEntity task = new PlanTaskEntity();
        task.setId(UUID.randomUUID());
        task.setPlanId(plan.getId());
        task.setUserId(plan.getUserId());
        task.setDirectionId(directionId);
        task.setTitle(title);
        task.setDescription("");
        task.setCategory("study");
        task.setPriority("normal");
        task.setStatus(PlanTaskEntity.STATUS_PENDING);
        task.setTargetMinutes(target);
        task.setProgressMinutes(0);
        task.setSource(PlanTaskEntity.SOURCE_MANUAL);
        taskRepository.save(task);
        return mapper.toTaskResponse(task);
    }

    /** 手动勾选/取消勾选：只改 status，不碰 progress（ADR §后果）。 */
    @Transactional
    public PlanTaskResponse patchTask(String userId, UUID planId, UUID taskId, PatchTaskRequest request) {
        if (!PlanTaskEntity.STATUS_PENDING.equals(request.status())
            && !PlanTaskEntity.STATUS_DONE.equals(request.status())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务状态需为 PENDING 或 DONE");
        }
        PlanTaskEntity task = taskRepository.findByIdAndPlanIdAndUserId(taskId, planId, UUID.fromString(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_TASK_NOT_FOUND));
        task.setStatus(request.status());
        taskRepository.save(task);
        return mapper.toTaskResponse(task);
    }

    /** 今日待办：全部 PENDING 任务跨计划，priority 降序（high→normal→low）后按创建序。 */
    @Transactional(readOnly = true)
    public List<PlanTaskResponse> today(String userId) {
        return taskRepository.findByUserIdAndStatusOrderByCreatedAtAsc(
                UUID.fromString(userId), PlanTaskEntity.STATUS_PENDING)
            .stream()
            .sorted(Comparator.comparingInt((PlanTaskEntity task) -> priorityRank(task.getPriority()))
                .thenComparing(PlanTaskEntity::getCreatedAt))
            .map(mapper::toTaskResponse)
            .toList();
    }

    private static int priorityRank(String priority) {
        return switch (priority) {
            case "high" -> 0;
            case "normal" -> 1;
            default -> 2;
        };
    }

    private PlanEntity ownedPlan(String userId, UUID planId) {
        return planRepository.findByIdAndUserId(planId, UUID.fromString(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "计划标题不能为空");
        }
        String trimmed = title.trim();
        if (trimmed.length() > PlanEntity.MAX_TITLE_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "计划标题不得超过 " + PlanEntity.MAX_TITLE_LENGTH + " 字符");
        }
        return trimmed;
    }

    /** 方向可见性校验；null 合法（可选归属）。返回解析后的 id 或 null。 */
    private UUID requireVisibleDirection(String userId, String directionId) {
        if (directionId == null || directionId.isBlank()) {
            return null;
        }
        if (!directions.existsVisibleTo(userId, directionId)) {
            throw new BusinessException(ErrorCode.DIRECTION_NOT_FOUND);
        }
        return UUID.fromString(directionId);
    }
}
