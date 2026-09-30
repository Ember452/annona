package io.annona.modules.evaluation.controller;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.evaluation.dto.EvaluationReportResponse;
import io.annona.modules.evaluation.service.EvaluationQueryService;
import io.annona.spi.dto.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估报告端点（P1b-06）。鉴权由 SessionAuthFilter 对 /api/** 强制；本类只做路由/委托。
 *
 * <p>错误码：3000 报告不存在或非本人（会话归属经报告行 user_id 校验，不泄露他人是否存在）。
 * 报告页轮询本端点：{@code status} 为 PENDING/RUNNING 时前端继续轮询，DONE 渲染完整、FAILED 报错。
 */
@RestController
@RequestMapping("/api/evaluation/sessions")
public class EvaluationController {

    private final EvaluationQueryService queryService;

    public EvaluationController(EvaluationQueryService queryService) {
        this.queryService = queryService;
    }

    /** GET /{sessionId}/report——读取某会话的评估报告（含逐题明细与整场汇总）。 */
    @GetMapping("/{sessionId}/report")
    public Result<EvaluationReportResponse> report(@CurrentPrincipal Principal principal,
                                                    @PathVariable String sessionId) {
        UUID userId;
        UUID sid;
        try {
            userId = UUID.fromString(principal.id());
            sid = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.EVALUATION_NOT_FOUND);
        }
        return Result.success(queryService.report(userId, sid));
    }
}
