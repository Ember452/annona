package io.annona.modules.planner.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.planner.dto.DecisionTraceResponse;
import io.annona.modules.planner.service.DecisionPanelService;
import io.annona.spi.dto.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可解释决策面板端点（P1c-06/07）。鉴权由 SessionAuthFilter 对 /api/** 强制（未登录 1004）；
 * 本类只做路由/归属主体提取/委托，编排在 {@link DecisionPanelService}。
 *
 * <p>错误码全谱：3200 决策留痕不存在或无权限 / 3201 该条已驳回过。
 */
@RestController
@RequestMapping("/api/decision")
public class DecisionController {

    private final DecisionPanelService panelService;

    public DecisionController(DecisionPanelService panelService) {
        this.panelService = panelService;
    }

    /**
     * GET /session/{sessionId}——单场面试的全部决策留痕（报告页决策理由节）。
     * 非本人会话返回空列表（不泄露存在性）。
     */
    @GetMapping("/session/{sessionId}")
    public Result<List<DecisionTraceResponse>> sessionTraces(@CurrentPrincipal Principal principal,
                                                             @PathVariable UUID sessionId) {
        return Result.success(panelService.sessionTraces(UUID.fromString(principal.id()), sessionId));
    }

    /**
     * GET /recent?limit=5——首页"最近 N 场"决策摘要（时间倒序）。
     */
    @GetMapping("/recent")
    public Result<List<DecisionTraceResponse>> recent(@CurrentPrincipal Principal principal,
                                                      @RequestParam(defaultValue = "5") int limit) {
        return Result.success(panelService.recentTraces(UUID.fromString(principal.id()), limit));
    }

    /**
     * POST /trace/{traceId}/reject——"这条不对"反驳：置驳回并累计规则声誉（达阈值停用该规则）。
     * 错误码：3200 / 3201。返回该规则累计驳回数（前端据此提示"已停用该规则"）。
     */
    @PostMapping("/trace/{traceId}/reject")
    public Result<Integer> reject(@CurrentPrincipal Principal principal,
                                  @PathVariable UUID traceId) {
        return Result.success(panelService.reject(UUID.fromString(principal.id()), traceId));
    }
}
