package io.annona.common.export;

import java.util.List;

/**
 * PDF 报告的内容模型（{@link ReportPdfRenderer} 端口的入参，infra 无关）：只放 JDK 类型，
 * 让实现方（iText）拿得到内容却看不到任何业务模块类型（AGENTS §4：SDK 只在 infrastructure，
 * 业务经 common 端口传中性数据）。evaluation 把报告装配成本记录再交给渲染端口。
 *
 * @param title          文档标题（如"面试评估报告 · 方向名"）
 * @param meta           元信息行（评估器版本、生成时间、模型留痕）
 * @param compositeScore 综合分 0..100（可空=未出分）
 * @param overview       整场小结（结论 + 亮点 + 改进，逐条）
 * @param questions      逐题块
 * @param rationale      "决策理由"节（可解释主张：分数怎么来的、降级说明）
 */
public record PdfReport(String title, String meta, Integer compositeScore,
                        List<String> overview, List<QuestionBlock> questions,
                        List<String> rationale) {

    /** 单题块。{@code scoreLabel} 为已格式化的分数串（"85 分" / "降级"），避免渲染层判 null。 */
    public record QuestionBlock(String heading, String scoreLabel, String feedback,
                                List<String> strengths, List<String> improvements, boolean degraded) {
    }

    public PdfReport {
        overview = overview == null ? List.of() : List.copyOf(overview);
        questions = questions == null ? List.of() : List.copyOf(questions);
        rationale = rationale == null ? List.of() : List.copyOf(rationale);
    }
}
