package io.annona.modules.evaluation.dto;

import io.annona.modules.evaluation.model.EvaluationSummary;
import java.util.List;
import java.util.UUID;

/**
 * 评估报告视图（{@code GET /api/evaluation/sessions/{id}/report}）。UUID 全 String、时间 ISO 串
 * （interview 视图同款口径）。PENDING/RUNNING 时 {@code compositeScore}/{@code summary}/{@code questions}
 * 可为空——报告页据 {@code status} 轮询，DONE 才渲染完整内容（interview_report 是出口①完成判据）。
 *
 * @param sessionId        会话 ID
 * @param status           PENDING|RUNNING|DONE|FAILED
 * @param evaluatorVersion 评估器版本
 * @param compositeScore   难度加权总分 0..100（未出分时 null）
 * @param summary          整场汇总（未生成时 null）
 * @param questions        逐题明细（未生成时空集）
 * @param chatModel        出题模型（可比性留痕）
 * @param evaluatorModel   评分模型（可比性留痕）
 * @param promptHash       评估提示哈希（可比性留痕）
 * @param error            失败原因（FAILED 时给可读文案）
 * @param generatedAt      报告更新时间 ISO 串
 */
public record EvaluationReportResponse(String sessionId, String status, String evaluatorVersion,
                                       Integer compositeScore, EvaluationSummary summary,
                                       List<QuestionView> questions, String chatModel,
                                       String evaluatorModel, String promptHash, String error,
                                       String generatedAt) {

    /** 逐题评估视图。 */
    public record QuestionView(UUID questionId, int followUpIndex, Integer score, String feedback,
                               List<String> strengths, List<String> improvements,
                               boolean fallbackUsed) {
    }
}
