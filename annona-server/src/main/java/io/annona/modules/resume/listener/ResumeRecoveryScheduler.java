package io.annona.modules.resume.listener;

import io.annona.modules.resume.entity.ResumeEntity;
import io.annona.modules.resume.repository.ResumeRepository;
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
 * 简历分析恢复调度（P1b-08，仿 EvaluationRecoveryScheduler 双阈值）：PENDING 超时未领（投递丢失）
 * → 补投；PROCESSING 超时（消费崩溃）→ 条件回退 PENDING 后补投。回退是 @Modifying，包短事务
 * （check-modifying-callers 机检）。与 ResumeStream 门控键不同（enabled vs recovery），stream 经 ObjectProvider。
 */
@Component
@ConditionalOnProperty(prefix = "annona.resume.recovery", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class ResumeRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResumeRecoveryScheduler.class);

    static final long PENDING_STALE_MS = 2 * 60 * 1000L;
    static final long PROCESSING_STALE_MS = 10 * 60 * 1000L;
    static final int SWEEP_LIMIT = 10;

    private final ResumeRepository resumeRepository;
    private final ObjectProvider<ResumeStream> stream;
    private final TransactionTemplate tx;

    public ResumeRecoveryScheduler(ResumeRepository resumeRepository,
                                   ObjectProvider<ResumeStream> stream,
                                   PlatformTransactionManager transactionManager) {
        this.resumeRepository = resumeRepository;
        this.stream = stream;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${annona.resume.recovery.interval-ms:60000}")
    public void recover() {
        Instant now = Instant.now();
        for (ResumeEntity pending : resumeRepository.findStale(
            ResumeEntity.STATUS_PENDING, now.minusMillis(PENDING_STALE_MS),
            PageRequest.of(0, SWEEP_LIMIT))) {
            log.info("简历恢复：补投 PENDING {}", pending.getId());
            sendIfAvailable(pending.getId());
        }
        for (ResumeEntity processing : resumeRepository.findStale(
            ResumeEntity.STATUS_PROCESSING, now.minusMillis(PROCESSING_STALE_MS),
            PageRequest.of(0, SWEEP_LIMIT))) {
            log.info("简历恢复：PROCESSING 超时，回退并重投 {}", processing.getId());
            UUID id = processing.getId();
            Integer reset = tx.execute(s -> resumeRepository.markProcessingBackToPending(id, now));
            if (reset != null && reset == 1) {
                sendIfAvailable(id);
            }
        }
    }

    private void sendIfAvailable(UUID resumeId) {
        Optional.ofNullable(stream.getIfAvailable()).ifPresentOrElse(
            s -> s.send(resumeId),
            () -> log.warn("简历分析流未启用，跳过补投（{} 留待下次）", resumeId));
    }
}
