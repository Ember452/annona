package io.annona.modules.knowledge.listener;

import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.repository.KbDocRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 入库恢复调度（借 🅖 VectorizeRecoveryScheduler 的双扫描 + touch 原子去重 + 计数上限）：
 * ① PENDING 超时 = 投递丢失，touch（recovery_count+1 且推进 updated_at，同期多实例只有
 * 一个拿到 1）→ 计数达上限判 FAILED，否则补投；② 在途超时（PARSING/CHUNKING/EMBEDDING
 * 无心跳）= 消费者崩溃，按代次条件重置回 PENDING 后补投（计数不增，毒文档由下一周期的
 * ① 兜住上限）。
 *
 * <p>enabled 门控（默认开）：@Scheduled bean 会被调度后置处理器在 context refresh 时
 * 强制提前实例化，绕过 test profile 的全局懒加载——无 JPA 的冒烟上下文必须能显式关掉它
 * （用属性门控而非 @ConditionalOnBean：后者在普通 @Configuration/component 上求值过早，
 * 恒为假——AGENTS 认知坑②）。条件 UPDATE 经 TransactionTemplate 自成短事务
 * （@Modifying 无调用方事务在真库上必抛，P1a-05 CI 实测教训）。
 */
@Component
@ConditionalOnProperty(prefix = "annona.knowledge.recovery", name = "enabled",
    havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KnowledgeRecoveryProperties.class)
public class KnowledgeRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRecoveryScheduler.class);

    private final KbDocRepository docRepository;
    private final TaskStreamPort taskStreamPort;
    private final KnowledgeRecoveryProperties properties;
    private final TransactionTemplate tx;

    public KnowledgeRecoveryScheduler(KbDocRepository docRepository,
        TaskStreamPort taskStreamPort, KnowledgeRecoveryProperties properties,
        PlatformTransactionManager transactionManager) {
        this.docRepository = docRepository;
        this.taskStreamPort = taskStreamPort;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 间隔走独立属性键（默认 60s，借 🅖 interval-ms）。 */
    @Scheduled(fixedDelayString = "${annona.knowledge.recovery.interval-ms:60000}")
    public void recover() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        recoverPendingDocs(now);
        recoverInFlightDocs(now);
    }

    private void recoverPendingDocs(Instant now) {
        List<KbDocEntity> stale = docRepository.findRecoveryCandidates(
            List.of(KbDocEntity.STATUS_PENDING), now.minus(properties.getPendingThreshold()));
        for (KbDocEntity doc : stale) {
            Integer touched = tx.execute(s -> docRepository.touchPendingForRecovery(doc.getId(),
                now.minus(properties.getPendingThreshold()), now));
            if (touched == null || touched == 0) {
                continue; // 已被其他调度实例恢复（touch 原子去重，借 🅖）
            }
            if (doc.getRecoveryCount() + 1 >= properties.getMaxRecoveryCount()) {
                tx.executeWithoutResult(s -> docRepository.markRecoveryExhausted(doc.getId(),
                    properties.getMaxRecoveryCount(), "自动恢复次数达到上限，请在文档列表中手动重试", now));
                log.warn("文档恢复次数达上限 docId={}", doc.getId());
                continue;
            }
            taskStreamPort.send(KnowledgeVectorizeStream.STREAM_KEY,
                Map.of("docId", doc.getId().toString()));
        }
    }

    private void recoverInFlightDocs(Instant now) {
        List<KbDocEntity> stale = docRepository.findRecoveryCandidates(
            List.of(KbDocEntity.STATUS_PARSING, KbDocEntity.STATUS_CHUNKING, KbDocEntity.STATUS_EMBEDDING),
            now.minus(properties.getProcessingThreshold()));
        for (KbDocEntity doc : stale) {
            // 代次匹配才重置：旧代次无法回收已被其他实例重新领取的任务
            Integer reset = tx.execute(s -> docRepository.resetStaleToPending(doc.getId(),
                doc.getAttemptId(), now));
            if (reset != null && reset == 1) {
                taskStreamPort.send(KnowledgeVectorizeStream.STREAM_KEY,
                Map.of("docId", doc.getId().toString()));
            }
        }
    }
}
