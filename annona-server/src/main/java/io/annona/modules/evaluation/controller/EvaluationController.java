package io.annona.modules.evaluation.controller;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.evaluation.dto.EvaluationReportResponse;
import io.annona.modules.evaluation.service.EvaluationExportService;
import io.annona.modules.evaluation.service.EvaluationQueryService;
import io.annona.spi.dto.Principal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final EvaluationExportService exportService;

    public EvaluationController(EvaluationQueryService queryService,
                                EvaluationExportService exportService) {
        this.queryService = queryService;
        this.exportService = exportService;
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

    /**
     * GET /{sessionId}/report.pdf——下载评估报告 PDF（iText 内置 CJK，中文不乱码）。
     * 非 {@link Result} 包装：这是传输级文件响应（application/pdf + 下载头），
     * 与 JSON API 拆包约定不同口径。归属/不存在同走 3000（由 export 服务抛出）。
     */
    @GetMapping("/{sessionId}/report.pdf")
    public ResponseEntity<byte[]> reportPdf(@CurrentPrincipal Principal principal,
                                            @PathVariable String sessionId) {
        UUID userId;
        UUID sid;
        try {
            userId = UUID.fromString(principal.id());
            sid = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.EVALUATION_NOT_FOUND);
        }
        byte[] pdf = exportService.exportPdf(userId, sid);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "attachment; filename=\"report-" + sid + ".pdf\"")
            .body(pdf);
    }
}
