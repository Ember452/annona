package io.annona.modules.evaluation.model;

import java.util.List;

/**
 * 会话级二次汇总正文（interview_report.summary JSONB）。由评估链的"汇总 prompt"产出，
 * 逐题明细另存 interview_evaluation。null 字段规整为空集合（JSON 往返不留 null 元素）。
 *
 * @param strengths    整场亮点
 * @param improvements 整场改进建议
 * @param overall      结论文本
 */
public record EvaluationSummary(List<String> strengths, List<String> improvements,
                                String overall) {

    public EvaluationSummary {
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        improvements = improvements == null ? List.of() : List.copyOf(improvements);
    }
}
