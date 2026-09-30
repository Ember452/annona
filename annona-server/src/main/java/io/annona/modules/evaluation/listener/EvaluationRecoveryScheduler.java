package io.annona.modules.evaluation.listener;

import io.annona.modules.evaluation.entity.InterviewReportEntity;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 评估恢复调度（P1b-06，仿 {@code QuestionGenRecoveryScheduler} 双阈值 + fencing）：
 * PENDING 超时未领（事件投递丢失/Redis 缺席）→ 补投；RUNNING 超时（消费实例崩溃）→
 * 条件回退 PENDING 后补投。回退是 {@code @Modifying}，必须包短事务（check-modifying-callers 机检）。
 * 与 EvaluationStream 门控键不同（enabled vs recovery）——组合存在缺席可能，stream 经 ObjectProvider。
 */
@Component
@ConditionalOnProperty(prefix = "annona.evaluation.recovery", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class EvaluationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRecoveryScheduler.class);

    /** PENDING 超过 2 分钟无人领取即补投（正常领取在秒级）。 */
    static final long PENDING_STALE_MS = 2 * 60 * 1000L;
    /** RUNNING 超过 20 分钟未完成视执行实例已死（逐题 LLM 上限远小于此）。 */
    static final long RUNNING_STALE_MS = 20 * 60 * 1000L;
    static final int SWEEP_LIMIT = 10;

    private final InterviewReportRepository reportRepository;
    private final ObjectProvider<EvaluationStream> stream;
    private final TransactionTemplate tx;

    public EvaluationRecoveryScheduler(InterviewReportRepository reportRepository,
                                       ObjectProvider<EvaluationStream> stream,
                                       PlatformTransactionManager transactionManager) {
        this.reportRepository = reportRepository;
        this.stream = stream;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${annona.evaluation.recovery.interval-ms:60000}")
    public void recover() {
        Instant now = Instant.now();
        for (InterviewReportEntity pending : reportRepository.findStale(
            InterviewReportEntity.STATUS_PENDING, now.minusMillis(PENDING_STALE_MS),
            PageRequest.of(0, SWEEP_LIMIT))) {
            log.info("评估恢复：补投 PENDING 报告 {}", pending.getSessionId());
            sendIfAvailable(pending.getSessionId());
        }
        for (InterviewReportEntity running : reportRepository.findStale(
            InterviewReportEntity.STATUS_RUNNING, now.minusMillis(RUNNING_STALE_MS),
            PageRequest.of(0, SWEEP_LIMIT))) {
            log.info("评估恢复：RUNNING 超时，回退并重投报告 {}", running.getSessionId());
            UUID sessionId = running.getSessionId();
            Integer reset = tx.execute(s -> reportRepository.markRunningBackToPending(
                sessionId, running.getEvaluatorVersion(), now));
            if (reset != null && reset == 1) {
                sendIfAvailable(sessionId);
            }
        }
    }

    private void sendIfAvailable(UUID sessionId) {
        Optional.ofNullable(stream.getIfAvailable()).ifPresentOrElse(
            s -> s.send(sessionId),
            () -> log.warn("评估流未启用，跳过补投（报告 {} 留待下次）", sessionId));
    }
}
