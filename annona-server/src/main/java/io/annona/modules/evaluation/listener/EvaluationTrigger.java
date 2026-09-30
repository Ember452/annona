package io.annona.modules.evaluation.listener;

import io.annona.modules.evaluation.entity.InterviewReportEntity;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import io.annona.modules.evaluation.service.EvaluationService;
import io.annona.shared.domain.InterviewFinalizedEvent;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 交卷事件监听器（evaluation-pipeline-adr §决策 7）：interview 交卷事务提交后（AFTER_COMMIT）建
 * 报告 PENDING 行 + 投递评估任务。interview 不直接依赖 evaluation（写走领域事件，AGENTS §4）；
 * 与 {@link EvaluationStream} 同门控——{@code annona.evaluation.enabled=false} 时本 bean 缺席，
 * 事件无人接、交卷照常返回（评估是尽力而为的后置副作用）。
 *
 * <p>{@code fallbackExecution=true}：交卷路径若无事务（理论上不该发生，防御），也在事件发布时
 * 立即执行而非丢弃。建报告用独立短事务（{@code REQUIRES_NEW}），不挂在交卷事务尾巴上。
 */
@Component
@ConditionalOnProperty(prefix = "annona.evaluation", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class EvaluationTrigger {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTrigger.class);

    private final InterviewReportRepository reportRepository;
    private final ObjectProvider<EvaluationStream> stream;

    public EvaluationTrigger(InterviewReportRepository reportRepository,
                             ObjectProvider<EvaluationStream> stream) {
        this.reportRepository = reportRepository;
        this.stream = stream;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInterviewFinalized(InterviewFinalizedEvent event) {
        // 幂等：同 session+version 已有报告行（事件重投/重复交卷）则不重建，只确保投递
        boolean created = reportRepository
            .findBySessionIdAndEvaluatorVersion(event.sessionId(), EvaluationService.EVALUATOR_VERSION)
            .isEmpty();
        if (created) {
            reportRepository.save(InterviewReportEntity.pending(event.sessionId(), event.userId(),
                EvaluationService.EVALUATOR_VERSION, Instant.now()));
        }
        Optional<EvaluationStream> streamBean = Optional.ofNullable(stream.getIfAvailable());
        if (streamBean.isEmpty()) {
            log.info("评估流未启用，交卷 {} 的报告留待恢复调度", event.sessionId());
            return;
        }
        if (!streamBean.get().send(event.sessionId())) {
            log.warn("评估投递失败，报告 {} 留 PENDING 待恢复调度补投", event.sessionId());
        }
    }
}
