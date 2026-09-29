package io.annona.modules.questionbank.controller;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.questionbank.dto.GenerateQuestionsRequest;
import io.annona.modules.questionbank.dto.QuestionGenStatusResponse;
import io.annona.modules.questionbank.listener.QuestionGenStream;
import io.annona.modules.questionbank.model.QuestionGenConfig;
import io.annona.modules.questionbank.service.QuestionGenStateService;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.progress.ProgressEvent;
import io.annona.shared.progress.SseProgressHub;
import io.annona.spi.dto.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识库出题端点（P1b-02）：发起生成 / 轮询状态 / SSE 进度。
 * 鉴权由 SessionAuthFilter 对 /api/** 强制且白名单不含本路径——未登录即 1004。
 * 全部端点按 (userId, directionId) 作用域；内置方向共享可读，任务与题目按用户隔离。
 */
@RestController
@RequestMapping("/api/questionbank/directions/{directionId}/questions")
public class QuestionBankController {

    private final QuestionGenStateService stateService;
    private final DirectionQueryService directionQuery;
    private final QuestionGenStream stream;
    private final SseProgressHub progressHub;

    public QuestionBankController(QuestionGenStateService stateService,
                                  DirectionQueryService directionQuery, QuestionGenStream stream,
                                  SseProgressHub progressHub) {
        this.stateService = stateService;
        this.directionQuery = directionQuery;
        this.stream = stream;
        this.progressHub = progressHub;
    }

    /**
     * POST /generate——发起异步出题，立即返回 QUEUED 任务状态。错误码：1001（参数越界）、
     * 2100（方向不可见）、2600（已有在途任务）、2601（任务投递失败，Redis 不可用）。
     */
    @PostMapping("/generate")
    public Result<QuestionGenStatusResponse> generate(@CurrentPrincipal Principal principal,
                                                      @PathVariable String directionId,
                                                      @RequestBody GenerateQuestionsRequest request) {
        int difficulty = requireRange(request.difficulty(), 1, 5, "difficulty");
        int questionCount = requireRange(request.questionCount(), 1, 30, "questionCount");
        int followUpCount = requireRange(request.followUpCount(), 0, 5, "followUpCount");
        if (!directionQuery.existsVisibleTo(principal.id(), directionId)) {
            throw new BusinessException(ErrorCode.DIRECTION_NOT_FOUND);
        }
        QuestionGenStatusResponse task = stateService.createTask(UUID.fromString(principal.id()),
            UUID.fromString(directionId),
            new QuestionGenConfig(difficulty, questionCount, followUpCount));
        progressHub.publish(UUID.fromString(directionId),
            new ProgressEvent("QUEUED", "任务已提交", 0, questionCount, ""));
        // 先落库后投递（借 🅖）；投递失败判 FAILED，恢复调度不会救"从未存在过的消息"——
        // 直接把错误还给用户重试，比静默等调度更可解释
        if (!stream.send(UUID.fromString(task.taskId()))) {
            stateService.markFailed(UUID.fromString(task.taskId()), "任务投递失败（Redis 不可用）");
            throw new BusinessException(ErrorCode.QB_GENERATION_FAILED);
        }
        return Result.success(task);
    }

    /**
     * GET /generation-status——该 (用户, 方向) 最近一次出题任务的状态（前端 3s 轮询兜底）。
     * 无历史任务时 data 为 null。错误码：2100（方向不可见）。
     */
    @GetMapping("/generation-status")
    public Result<QuestionGenStatusResponse> status(@CurrentPrincipal Principal principal,
                                                    @PathVariable String directionId) {
        requireVisible(principal, directionId);
        return Result.success(
            stateService.latestStatus(UUID.fromString(principal.id()), UUID.fromString(directionId))
                .orElse(null));
    }

    /**
     * GET /progress——出题进度 SSE（事件名 progress，信封 shared/progress.ProgressEvent）。
     * 实时推送失败由前端轮询 generation-status 兜底（knowledge 同款分工）。
     * 错误码：2100（方向不可见）。SSE 已建立后的失败走 FAILED 事件，不走 HTTP 状态。
     */
    @GetMapping(value = "/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@CurrentPrincipal Principal principal,
                               @PathVariable String directionId) {
        requireVisible(principal, directionId);
        return progressHub.subscribe(UUID.fromString(directionId));
    }

    private void requireVisible(Principal principal, String directionId) {
        if (!directionQuery.existsVisibleTo(principal.id(), directionId)) {
            throw new BusinessException(ErrorCode.DIRECTION_NOT_FOUND);
        }
    }

    private int requireRange(Integer value, int min, int max, String field) {
        if (value == null || value < min || value > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                field + " 需在 " + min + "–" + max + " 之间");
        }
        return value;
    }

}
