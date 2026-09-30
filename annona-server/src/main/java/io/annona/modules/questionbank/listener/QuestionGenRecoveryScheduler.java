package io.annona.modules.questionbank.listener;

import io.annona.modules.questionbank.entity.QbGenerationTaskEntity;
import io.annona.modules.questionbank.repository.QbGenerationTaskRepository;
import java.time.Instant;
import java.util.List;
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
 * 出题任务恢复调度（借 🅖 双阈值，fencing 沿用 taskId+状态）：
 * QUEUED 超时未领（投递丢失/消费实例崩溃）→ 重投；PROCESSING 超时（执行实例崩溃）→
 * 重置回 QUEUED 后重投。重投是幂等的——重复消息被 tryMarkProcessing 的条件领取挡住。
 * 与 knowledge 恢复调度同款门控：@Scheduled 会被强制实例化，无 Redis 上下文必须能关。
 */
@Component
@ConditionalOnProperty(prefix = "annona.questionbank", name = "recovery.enabled",
    havingValue = "true", matchIfMissing = true)
public class QuestionGenRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenRecoveryScheduler.class);

    /** QUEUED 超过 2 分钟无人领取即补投（正常领取在秒级）。 */
    static final long QUEUED_STALE_MS = 2 * 60 * 1000L;
    /** PROCESSING 超过 20 分钟未完成视执行实例已死（LLM 生成上限远小于此）。 */
    static final long PROCESSING_STALE_MS = 20 * 60 * 1000L;
    /** 单轮扫描上限，防止故障恢复期打爆流。 */
    static final int SWEEP_LIMIT = 10;

    private final QbGenerationTaskRepository taskRepository;
    /** stream 与本调度器的门控键不同（generate vs recovery），组合存在缺席可能——禁止硬注入。 */
    private final ObjectProvider<QuestionGenStream> stream;
    private final TransactionTemplate tx;

    public QuestionGenRecoveryScheduler(QbGenerationTaskRepository taskRepository,
                                        ObjectProvider<QuestionGenStream> stream,
                                        PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.stream = stream;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${annona.questionbank.recovery.interval-ms:60000}")
    public void recover() {
        Instant now = Instant.now();
        List<QbGenerationTaskEntity> stuckQueued = taskRepository.findStale(
            QbGenerationTaskEntity.STATUS_QUEUED, now.minusMillis(QUEUED_STALE_MS),
            PageRequest.of(0, SWEEP_LIMIT));
        for (QbGenerationTaskEntity task : stuckQueued) {
            log.info("恢复调度：补投 QUEUED 任务 {}", task.getId());
            streamIfAvailable().ifPresentOrElse(s -> s.send(task.getId()),
                () -> log.warn("出题流未启用，跳过补投（任务 {} 留待下次）", task.getId()));
        }
        List<QbGenerationTaskEntity> stuckProcessing = taskRepository.findStale(
            QbGenerationTaskEntity.STATUS_PROCESSING, now.minusMillis(PROCESSING_STALE_MS),
            PageRequest.of(0, SWEEP_LIMIT));
        for (QbGenerationTaskEntity task : stuckProcessing) {
            log.info("恢复调度：PROCESSING 超时，重置并重投任务 {}", task.getId());
            // resetForRetry 是 @Modifying：@Scheduled 线程无活动事务，必包短事务
            // （同 KnowledgeRecoveryScheduler；check-modifying-callers 门禁抓出的同型地雷）。
            // stream.send（Redis 投递）留在事务外：外部 IO 不进 DB 事务。
            UUID taskId = task.getId();
            Integer reset = tx.execute(s -> taskRepository.resetForRetry(taskId, now));
            if (reset != null && reset > 0) {
                streamIfAvailable().ifPresentOrElse(s -> s.send(taskId),
                    () -> log.warn("出题流未启用，跳过重投（任务 {} 留待下次）", taskId));
            }
        }
    }

    private java.util.Optional<QuestionGenStream> streamIfAvailable() {
        return java.util.Optional.ofNullable(stream.getIfAvailable());
    }
}
