package io.annona.modules.plan.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.plan.dto.CreatePlanRequest;
import io.annona.modules.plan.dto.CreateTaskRequest;
import io.annona.modules.plan.dto.PatchTaskRequest;
import io.annona.modules.plan.dto.PlanDetailResponse;
import io.annona.modules.plan.dto.PlanSummaryResponse;
import io.annona.modules.plan.dto.PlanTaskResponse;
import io.annona.modules.plan.dto.SplitResponse;
import io.annona.modules.plan.dto.StudioChatRequest;
import io.annona.modules.plan.dto.UpdatePlanRequest;
import io.annona.modules.plan.service.PlanService;
import io.annona.modules.plan.service.PlanSplitService;
import io.annona.modules.plan.service.StudioChatService;
import io.annona.spi.dto.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * plan 计划与任务端点（P2-06，plan-module-adr）。
 *
 * <p>路由一览：GET/POST /api/plans · GET/PATCH/DELETE /api/plans/{id} ·
 * POST /api/plans/{id}/split（AI 拆任务，同步）· POST /api/plans/{id}/tasks（手动追加）·
 * PATCH /api/plans/{id}/tasks/{taskId}（勾选）· GET /api/plans/tasks/today（今日待办）·
 * POST /api/plans/{id}/studio/chat（SSE 工作室对话）。
 * studio/chat 的 SSE 已建立后业务失败走 error 事件，不走 HTTP 状态（qa 同口径）。
 * 门控 annona.plan.enabled（缺省开）。
 */
@RestController
@RequestMapping("/api/plans")
@ConditionalOnProperty(value = "annona.plan.enabled", havingValue = "true", matchIfMissing = true)
public class PlanController {

    private final PlanService planService;
    private final PlanSplitService splitService;
    private final StudioChatService studioChatService;

    public PlanController(PlanService planService, PlanSplitService splitService,
                          StudioChatService studioChatService) {
        this.planService = planService;
        this.splitService = splitService;
        this.studioChatService = studioChatService;
    }

    @GetMapping
    public Result<List<PlanSummaryResponse>> list(@CurrentPrincipal Principal principal) {
        return Result.success(planService.list(principal.id()));
    }

    @PostMapping
    public Result<PlanDetailResponse> create(@CurrentPrincipal Principal principal,
                                             @RequestBody CreatePlanRequest request) {
        return Result.success(planService.create(principal.id(), request));
    }

    @GetMapping("/{id}")
    public Result<PlanDetailResponse> detail(@CurrentPrincipal Principal principal,
                                             @PathVariable String id) {
        return Result.success(planService.detail(principal.id(), UUID.fromString(id)));
    }

    @PatchMapping("/{id}")
    public Result<PlanDetailResponse> update(@CurrentPrincipal Principal principal,
                                             @PathVariable String id,
                                             @RequestBody UpdatePlanRequest request) {
        return Result.success(planService.update(principal.id(), UUID.fromString(id), request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentPrincipal Principal principal, @PathVariable String id) {
        planService.delete(principal.id(), UUID.fromString(id));
        return Result.success();
    }

    @PostMapping("/{id}/split")
    public Result<SplitResponse> split(@CurrentPrincipal Principal principal,
                                       @PathVariable String id) {
        return Result.success(splitService.split(principal.id(), UUID.fromString(id)));
    }

    @PostMapping("/{id}/tasks")
    public Result<PlanTaskResponse> addTask(@CurrentPrincipal Principal principal,
                                            @PathVariable String id,
                                            @RequestBody CreateTaskRequest request) {
        return Result.success(planService.addTask(principal.id(), UUID.fromString(id), request));
    }

    @PatchMapping("/{id}/tasks/{taskId}")
    public Result<PlanTaskResponse> patchTask(@CurrentPrincipal Principal principal,
                                              @PathVariable String id,
                                              @PathVariable String taskId,
                                              @RequestBody PatchTaskRequest request) {
        return Result.success(planService.patchTask(principal.id(), UUID.fromString(id),
            UUID.fromString(taskId), request));
    }

    @GetMapping("/tasks/today")
    public Result<List<PlanTaskResponse>> today(@CurrentPrincipal Principal principal) {
        return Result.success(planService.today(principal.id()));
    }

    @PostMapping("/{id}/studio/chat")
    public SseEmitter chat(@CurrentPrincipal Principal principal,
                           @PathVariable String id,
                           @RequestBody StudioChatRequest request) {
        return studioChatService.chat(principal.id(), UUID.fromString(id), request);
    }
}
