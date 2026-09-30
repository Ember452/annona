package io.annona.modules.evaluation.service;

import io.annona.common.export.PdfReport;
import io.annona.common.export.ReportPdfRenderer;
import io.annona.modules.evaluation.dto.EvaluationReportResponse;
import io.annona.modules.evaluation.dto.EvaluationReportResponse.QuestionView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 把评估报告装配成中性 {@link PdfReport} 并交 {@link ReportPdfRenderer} 渲染为 PDF 字节
 * （P1b-09）。业务模块只见 common 端口，不 import iText（AGENTS §4）。按需同步生成——
 * 单用户自部署、报告体量小（一场 ≤ 数十题），异步 + 对象存储的额外失败面在此不值（见
 * pdf-export-itext-adr 的同步取舍与重评触发）。
 */
@Service
public class EvaluationExportService {

    private final EvaluationQueryService queryService;
    private final ReportPdfRenderer renderer;

    public EvaluationExportService(EvaluationQueryService queryService, ReportPdfRenderer renderer) {
        this.queryService = queryService;
        this.renderer = renderer;
    }

    public byte[] exportPdf(UUID userId, UUID sessionId) {
        return renderer.render(toData(queryService.report(userId, sessionId)));
    }

    private PdfReport toData(EvaluationReportResponse report) {
        List<String> overview = new ArrayList<>();
        if (report.summary() != null) {
            if (report.summary().overall() != null && !report.summary().overall().isBlank()) {
                overview.add("结论：" + report.summary().overall());
            }
            report.summary().strengths().forEach(s -> overview.add("亮点：" + s));
            report.summary().improvements().forEach(s -> overview.add("改进：" + s));
        }
        List<PdfReport.QuestionBlock> questions = new ArrayList<>();
        int questionNo = 0;
        UUID lastQuestionId = null;
        for (QuestionView q : report.questions()) {
            if (!q.questionId().equals(lastQuestionId)) {
                questionNo++;
                lastQuestionId = q.questionId();
            }
            String heading = "题目 " + questionNo + (q.followUpIndex() == 0 ? "（主问题）"
                : "（追问 " + q.followUpIndex() + "）");
            String scoreLabel = q.fallbackUsed() ? "降级（无评分）" : (q.score() == null ? "—" : q.score() + " 分");
            questions.add(new PdfReport.QuestionBlock(heading, scoreLabel, q.feedback(),
                q.strengths(), q.improvements(), q.fallbackUsed()));
        }
        long degraded = report.questions().stream().filter(QuestionView::fallbackUsed).count();
        List<String> rationale = new ArrayList<>();
        rationale.add("综合分为逐题得分按题目难度加权（难度 1..5 → 权重 1.0..1.5），四舍五入到 0..100。");
        if (degraded > 0) {
            rationale.add("其中 " + degraded + " 题评估降级（模型输出不可解析），未计入综合分，逐题保留原文待复核。");
        }
        if (report.evaluatorModel() != null) {
            rationale.add("评分模型：" + report.evaluatorModel() + "；评估器版本：" + report.evaluatorVersion() + "。");
        }
        return new PdfReport("面试评估报告", meta(report), report.compositeScore(), overview,
            questions, rationale);
    }

    private static String meta(EvaluationReportResponse report) {
        StringBuilder sb = new StringBuilder("状态 ").append(report.status())
            .append(" · 评估器 ").append(report.evaluatorVersion());
        if (report.evaluatorModel() != null) {
            sb.append(" · 评分模型 ").append(report.evaluatorModel());
        }
        return sb.toString();
    }
}
