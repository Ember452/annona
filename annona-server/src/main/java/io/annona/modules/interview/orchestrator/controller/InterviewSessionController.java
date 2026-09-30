package io.annona.modules.interview.orchestrator.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.interview.orchestrator.session.InterviewSessionFacade;
import io.annona.spi.dto.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面试会话端点（P1b-04/05）。鉴权由 SessionAuthFilter 对 /api/** 强制（未登录 1004）；
 * 本类只做路由/参数转换/委托，编排在 {@link InterviewSessionFacade}。
 *
 * <p>错误码全谱：1001 计划或参数非法 / 2100 方向不可见 / 2604 容量不足（M7 复用批 1）/
 * 2701 会话不存在或非本人 / 2702 已交卷或已放弃 / 2703 作答槽位不符。
 */
@RestController
@RequestMapping("/api/interview/sessions")
public class InterviewSessionController {

    private final InterviewSessionFacade facade;

    public InterviewSessionController(InterviewSessionFacade facade) {
        this.facade = facade;
    }

    /**
     * POST /——开始面试（校验计划→组卷→落会话与占位→返回含首题的完整视图）。
     * 错误码：1001 / 2100 / 2604。
     */
    @PostMapping
    public Result<SessionView> create(@CurrentPrincipal Principal principal,
                                      @RequestBody CreateSessionRequest request) {
        return Result.success(facade.create(UUID.fromString(principal.id()), request));
    }

    /**
     * GET ?directionId=&status=RESUMABLE——在途会话列表（断线重进入口）。
     * status 目前仅 RESUMABLE 有意义（终态历史面板批 3 随报告一起开）。
     */
    @GetMapping
    public Result<List<SessionSummary>> list(@CurrentPrincipal Principal principal,
                                             @RequestParam(required = false) String directionId) {
        return Result.success(facade.listResumable(UUID.fromString(principal.id()),
            directionId == null ? null : UUID.fromString(directionId)));
    }

    /**
     * GET /{id}——会话完整视图（当前题、进度、已答回显；快照 miss 回落 DB 重建）。
     * 错误码：2701。
     */
    @GetMapping("/{id}")
    public Result<SessionView> get(@CurrentPrincipal Principal principal,
                                   @PathVariable UUID id) {
        return Result.success(facade.get(UUID.fromString(principal.id()), id));
    }

    /**
     * POST /{id}/answers——单槽作答（主问题或追问；条件 UPDATE，成功后推进恢复位）。
     * 错误码：2701 / 2702 / 2703。
     */
    @PostMapping("/{id}/answers")
    public Result<Boolean> answer(@CurrentPrincipal Principal principal, @PathVariable UUID id,
                                  @RequestBody AnswerRequest request) {
        return Result.success(facade.answer(UUID.fromString(principal.id()), id,
            UUID.fromString(request.questionId()), request.followUpIndex(),
            request.answerText()));
    }

    /**
     * POST /{id}/finalize——交卷（幂等：重复提交不产生双份记录，第二次按 2702 出口；
     * 批 2 只落库不评分，评分在批 3）。错误码：2701 / 2702。
     */
    @PostMapping("/{id}/finalize")
    public Result<FinalizeView> finalizeSession(@CurrentPrincipal Principal principal,
                                                @PathVariable UUID id) {
        return Result.success(facade.finalizeSession(UUID.fromString(principal.id()), id));
    }

    /**
     * POST /{id}/abandon——手动放弃会话（M6 入口二；新建同方向会话时旧在途自动废弃是入口一）。
     * 错误码：2701 / 2702（已终态不可放弃）。
     */
    @PostMapping("/{id}/abandon")
    public Result<Boolean> abandon(@CurrentPrincipal Principal principal, @PathVariable UUID id) {
        return Result.success(facade.abandon(UUID.fromString(principal.id()), id));
    }
}
