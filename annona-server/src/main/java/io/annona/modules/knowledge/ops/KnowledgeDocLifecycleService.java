package io.annona.modules.knowledge.ops;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.storage.ObjectStorage;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.listener.KnowledgeVectorizeStream;
import io.annona.modules.knowledge.repository.KbDocRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 文档生命周期：删除级联（借 🅖 KnowledgeBaseDeleteService 的顺序——事务内删行，
 * 事务提交后删 S3 对象且失败仅告警，可按 storage_key 补偿）与手动 re-vectorize
 * （重建式重嵌，ADR §决策 7：READY/FAILED → PENDING，恢复计数清零）。
 */
@Service
public class KnowledgeDocLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocLifecycleService.class);

    private final KbDocRepository docRepository;
    private final Optional<ObjectStorage> objectStorage;
    /**
     * 入库通道可选注入（P1a-05 CI 实测）：{@code KnowledgeVectorizeStream} 被
     * {@code annona.knowledge.ingest.enabled} 门控（docker profile 默认 false），硬注入会让
     * 所有完整上下文的 IT 连坐炸掉（27 errors）——违背 knowledge-ingestion-adr §决策 9
     * 的"通道关闭应用照常起，操作时报 23xx"。与 {@code Optional<ObjectStorage>} 同款。
     */
    private final Optional<KnowledgeVectorizeStream> vectorizeStream;
    private final TransactionTemplate tx;

    public KnowledgeDocLifecycleService(KbDocRepository docRepository,
        Optional<ObjectStorage> objectStorage,
        Optional<KnowledgeVectorizeStream> vectorizeStream,
        PlatformTransactionManager transactionManager) {
        this.docRepository = docRepository;
        this.objectStorage = objectStorage;
        this.vectorizeStream = vectorizeStream;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 删除文档：分块行经外键 ON DELETE CASCADE 同事务清理；S3 对象经 afterCommit
     * 同步器在<b>事务提交成功后</b>删除（§0.3 铁律：S3 调用不得进事务；先删对象后回滚
     * 会留下指向悬空 key 的 DB 行）。删除失败不回滚 DB——原文丢失可容忍，文档已不可达，
     * 残留对象可按 key 补偿。
     */
    @Transactional
    public void delete(String userId, String docId) {
        KbDocEntity doc = docRepository.findByIdForUpdate(parse(docId))
            .filter(entity -> entity.getUserId().equals(parse(userId)))
            .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOC_NOT_FOUND));
        docRepository.delete(doc);
        objectStorage.ifPresent(storage -> TransactionSynchronizationManager
            .registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        storage.delete(doc.getStorageKey());
                    } catch (RuntimeException e) {
                        log.warn("S3 对象删除失败（可按 key 补偿） storageKey={}", doc.getStorageKey(), e);
                    }
                }
            }));
    }

    /**
     * 手动重嵌：仅 READY/FAILED 可重排；在途文档报 2308。投递失败判 FAILED 并抛 2307。
     * 入库通道关闭（ingest.enabled=false）时直接抛 2307 且<b>不落任何库写</b>——
     * 先判后写，避免文档重排进 PENDING 后永远没有消费者认领。
     */
    public void revectorize(String userId, String docId) {
        KnowledgeVectorizeStream stream = vectorizeStream.orElseThrow(() ->
            new BusinessException(ErrorCode.KB_DOC_ENQUEUE_FAILED,
                "知识入库通道未启用（annona.knowledge.ingest.enabled=false），无法重嵌"));
        KbDocEntity doc = docRepository.findByIdAndUserId(parse(docId), parse(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOC_NOT_FOUND));
        Integer requeued = tx.execute(s -> docRepository.requeueForRevectorize(doc.getId(), Instant.now()));
        if (requeued == null || requeued == 0) {
            throw new BusinessException(ErrorCode.KB_DOC_STATE_CONFLICT);
        }
        if (!stream.send(doc.getId())) {
            tx.executeWithoutResult(s -> docRepository.markFailedIfPending(doc.getId(),
                "处理任务投递失败，请稍后重试", Instant.now()));
            throw new BusinessException(ErrorCode.KB_DOC_ENQUEUE_FAILED);
        }
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.KB_DOC_NOT_FOUND);
        }
    }
}
