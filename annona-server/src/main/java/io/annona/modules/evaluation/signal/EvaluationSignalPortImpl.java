package io.annona.modules.evaluation.signal;

import io.annona.modules.evaluation.repository.InterviewReportRepository;
import io.annona.modules.evaluation.repository.InterviewEvaluationRepository;
import io.annona.shared.evaluation.EvaluationSignalPort;
import io.annona.spi.dto.SessionOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link EvaluationSignalPort} 的 evaluation 实现（P1c-01）：单方向最近若干场 DONE 报告的逐场结果
 * （含四留痕），供掌握度事件与 VERSION_BASELINE 比对。委托 {@code findLatestDoneOutcomes} 的原生
 * JOIN，不 import interview 实体。取数不按时间窗口（理由见端口 Javadoc）。
 */
@Service
public class EvaluationSignalPortImpl implements EvaluationSignalPort {

    private final InterviewReportRepository reportRepository;
    private final InterviewEvaluationRepository evaluationRepository;

    public EvaluationSignalPortImpl(InterviewReportRepository reportRepository,
                                    InterviewEvaluationRepository evaluationRepository) {
        this.reportRepository = reportRepository;
        this.evaluationRepository = evaluationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionOutcome> latestOutcomes(UUID userId, UUID directionId, int limit) {
        return reportRepository.findLatestDoneOutcomes(userId, directionId, limit)
            .stream()
            .map(EvaluationSignalPortImpl::toOutcome)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> weakestQuestionIds(UUID userId, UUID directionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return evaluationRepository.findWeakestQuestionIds(userId, directionId, limit);
    }

    private static SessionOutcome toOutcome(Object[] row) {
        Integer score = row[3] instanceof Number n ? n.intValue() : null;
        return new SessionOutcome(
            (String) row[0],
            (String) row[1],
            score,
            toInstant(row[2]),
            (String) row[4],
            (String) row[5],
            (String) row[6],
            (String) row[7]);
    }

    /** timestamptz 原样读出为 java.sql.Timestamp（PG 驱动），统一转 Instant；null 透传。 */
    private static Instant toInstant(Object ts) {
        return ts instanceof Timestamp t ? t.toInstant() : null;
    }
}
