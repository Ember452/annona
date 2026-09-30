package io.annona.modules.evaluation.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.evaluation.dto.EvaluationReportResponse;
import io.annona.modules.evaluation.entity.InterviewEvaluationEntity;
import io.annona.modules.evaluation.entity.InterviewReportEntity;
import io.annona.modules.evaluation.repository.InterviewEvaluationRepository;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评估报告只读服务（报告页与轮询消费）。归属校验：非 owner 与"报告不存在"一律 3000，
 * 不泄露他人会话是否存在（interview 会话 2500/2701 同款口径）。DONE 前也能读——返回
 * 当前 status（供前端轮询），逐题明细随已落库部分给出。
 */
@Service
public class EvaluationQueryService {

    private final InterviewReportRepository reportRepository;
    private final InterviewEvaluationRepository evaluationRepository;

    public EvaluationQueryService(InterviewReportRepository reportRepository,
                                  InterviewEvaluationRepository evaluationRepository) {
        this.reportRepository = reportRepository;
        this.evaluationRepository = evaluationRepository;
    }

    @Transactional(readOnly = true)
    public EvaluationReportResponse report(UUID userId, UUID sessionId) {
        InterviewReportEntity report = reportRepository
            .findBySessionIdAndEvaluatorVersion(sessionId, EvaluationService.EVALUATOR_VERSION)
            .filter(r -> r.getUserId().equals(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.EVALUATION_NOT_FOUND));
        List<InterviewEvaluationEntity> rows = evaluationRepository
            .findBySessionIdAndEvaluatorVersionOrderByQuestionIdAscFollowUpIndexAsc(
                sessionId, EvaluationService.EVALUATOR_VERSION);
        List<EvaluationReportResponse.QuestionView> questions = rows.stream()
            .map(r -> new EvaluationReportResponse.QuestionView(r.getQuestionId(),
                r.getFollowUpIndex(), r.getScore() == null ? null : r.getScore().intValue(),
                r.getFeedback(), r.getStrengths(), r.getImprovements(), r.isFallbackUsed()))
            .toList();
        return new EvaluationReportResponse(report.getSessionId().toString(), report.getStatus(),
            report.getEvaluatorVersion(),
            report.getCompositeScore() == null ? null : report.getCompositeScore().intValue(),
            report.getSummary(), questions, report.getChatModel(), report.getEvaluatorModel(),
            report.getPromptHash(), report.getError(), report.getUpdatedAt().toString());
    }
}
