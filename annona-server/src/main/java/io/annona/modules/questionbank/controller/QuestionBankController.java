package io.annona.modules.questionbank.controller;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.questionbank.dto.CapacityResponse;
import io.annona.modules.questionbank.dto.GenerateQuestionsRequest;
import io.annona.modules.questionbank.dto.QuestionGenStatusResponse;
import io.annona.modules.questionbank.dto.QuestionResponse;
import io.annona.modules.questionbank.dto.UpdateQuestionRequest;
import io.annona.modules.questionbank.dto.UpdateQuestionStatusRequest;
import io.annona.modules.questionbank.listener.QuestionGenStream;
import io.annona.modules.questionbank.model.QuestionGenConfig;
import io.annona.modules.questionbank.service.QuestionBankService;
import io.annona.modules.questionbank.service.QuestionGenStateService;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.progress.ProgressEvent;
import io.annona.shared.progress.SseProgressHub;
import io.annona.spi.dto.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识库出题与题库端点（P1b-02/03）：发起生成 / 轮询状态 / SSE 进度 / 题库维护 / 容量校验。
 * 鉴权由 SessionAuthFilter 对 /api/** 强制且白名单不含本路径——未登录即 1004。
 * 全部端点按 (userId, directionId) 作用域；内置方向共享可读，任务与题目按用户隔离。
 */
@RestController
@RequestMapping("/api/questionbank/directions/{directionId}/questions")
public class QuestionBankController {

    private final QuestionGenStateService stateService;
    private final QuestionBankService bankService;
    private final DirectionQueryService directionQuery;
    /** 门控 bean（generate.enabled）——常驻 bean 禁止硬注入，守卫 IT 的约定。 */
    private final ObjectProvider<QuestionGenStream> stream;
    private final SseProgressHub progressHub;

    public QuestionBankController(QuestionGenStateService stateService,
                                  QuestionBankService bankService,
                                  DirectionQueryService directionQuery,
                                  ObjectProvider<QuestionGenStream> stream,
                                  SseProgressHub progressHub) {
        this.stateService = stateService;
        this.bankService = bankService;
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
        if (!streamProvider().send(UUID.fromString(task.taskId()))) {
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

    /**
     * GET ""——题库列表（可空过滤：status/difficulty/keyword）。错误码：2100（方向不可见）。
     */
    @GetMapping
    public Result<List<QuestionResponse>> list(@CurrentPrincipal Principal principal,
                                               @PathVariable String directionId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Short difficulty,
                                               @RequestParam(required = false) String keyword) {
        requireVisible(principal, directionId);
        return Result.success(bankService.list(UUID.fromString(principal.id()),
            UUID.fromString(directionId), status, difficulty, keyword));
    }

    /**
     * GET /capacity——容量校验（追问数硬约束，0..5 逐档返回 available 与 selectable）。
     * 错误码：2100（方向不可见）、1001（mainQuestionCount 越界）。
     */
    @GetMapping("/capacity")
    public Result<CapacityResponse> capacity(@CurrentPrincipal Principal principal,
                                             @PathVariable String directionId,
                                             @RequestParam Short difficulty,
                                             @RequestParam Integer mainQuestionCount) {
        requireVisible(principal, directionId);
        if (mainQuestionCount == null || mainQuestionCount < 1 || mainQuestionCount > 20) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "mainQuestionCount 需在 1–20 之间");
        }
        if (difficulty == null || difficulty < 1 || difficulty > 5) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "difficulty 需在 1–5 之间");
        }
        return Result.success(bankService.capacity(UUID.fromString(principal.id()),
            UUID.fromString(directionId), difficulty, mainQuestionCount));
    }

    /**
     * PUT /{questionId}——编辑题目（null 字段不更新）。错误码：2603（题目不存在/非本人）、
     * 2100。
     */
    @PutMapping("/{questionId}")
    public Result<QuestionResponse> update(@CurrentPrincipal Principal principal,
                                           @PathVariable String directionId,
                                           @PathVariable String questionId,
                                           @RequestBody UpdateQuestionRequest request) {
        requireVisible(principal, directionId);
        return Result.success(bankService.update(UUID.fromString(principal.id()),
            UUID.fromString(questionId), request));
    }

    /**
     * PUT /{questionId}/status——状态变更（DRAFT ↔ ACTIVE、任意 → ARCHIVED，归档不复活）。
     * 错误码：2603、1001（status 非法）、2100。
     */
    @PutMapping("/{questionId}/status")
    public Result<QuestionResponse> changeStatus(@CurrentPrincipal Principal principal,
                                                 @PathVariable String directionId,
                                                 @PathVariable String questionId,
                                                 @RequestBody UpdateQuestionStatusRequest request) {
        requireVisible(principal, directionId);
        return Result.success(bankService.changeStatus(UUID.fromString(principal.id()),
            UUID.fromString(questionId), request.status()));
    }

    /**
     * DELETE /{questionId}——物理删除（批 1 无作答记录；有作答史后走 ARCHIVED）。
     * 错误码：2603、2100。
     */
    @DeleteMapping("/{questionId}")
    public Result<Void> delete(@CurrentPrincipal Principal principal,
                               @PathVariable String directionId,
                               @PathVariable String questionId) {
        requireVisible(principal, directionId);
        bankService.delete(UUID.fromString(principal.id()), UUID.fromString(questionId));
        return Result.success();
    }

    /** 出题后台机器被门控关闭时给出可理解错误，而不是 NoSuchBean 硬崩。 */
    private QuestionGenStream streamProvider() {
        QuestionGenStream streamBean = stream.getIfAvailable();
        if (streamBean == null) {
            throw new BusinessException(ErrorCode.QB_GENERATION_FAILED, "出题服务未启用");
        }
        return streamBean;
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
