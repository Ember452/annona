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
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档生命周期：删除级联（借 🅖 KnowledgeBaseDeleteService 的顺序——事务内删行，
 * 事务外删 S3 对象且失败仅告警，可按 storage_key 补偿）与手动 re-vectorize
 * （重建式重嵌，ADR §决策 7：READY/FAILED → PENDING，恢复计数清零）。
 */
@Service
public class KnowledgeDocLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocLifecycleService.class);

    private final KbDocRepository docRepository;
    private final Optional<ObjectStorage> objectStorage;
    private final KnowledgeVectorizeStream vectorizeStream;

    public KnowledgeDocLifecycleService(KbDocRepository docRepository,
        Optional<ObjectStorage> objectStorage, KnowledgeVectorizeStream vectorizeStream) {
        this.docRepository = docRepository;
        this.objectStorage = objectStorage;
        this.vectorizeStream = vectorizeStream;
    }

    /**
     * 删除文档：分块行经外键 ON DELETE CASCADE 同事务清理；S3 对象在事务提交后删除
     * （失败不回滚 DB——原文丢失可容忍，文档已不可达；残留对象可按 key 补偿）。
     */
    @Transactional
    public void delete(String userId, String docId) {
        KbDocEntity doc = docRepository.findByIdForUpdate(parse(docId))
            .filter(entity -> entity.getUserId().toString().equals(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOC_NOT_FOUND));
        docRepository.delete(doc);
        objectStorage.ifPresent(storage -> {
            try {
                storage.delete(doc.getStorageKey());
            } catch (RuntimeException e) {
                log.warn("S3 对象删除失败（可按 key 补偿） storageKey={}", doc.getStorageKey(), e);
            }
        });
    }

    /** 手动重嵌：仅 READY/FAILED 可重排；在途文档报 2308。投递失败判 FAILED 并抛 2307。 */
    public void revectorize(String userId, String docId) {
        KbDocEntity doc = docRepository.findByIdAndUserId(parse(docId), parse(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOC_NOT_FOUND));
        if (docRepository.requeueForRevectorize(doc.getId(), Instant.now()) == 0) {
            throw new BusinessException(ErrorCode.KB_DOC_STATE_CONFLICT);
        }
        if (!vectorizeStream.send(doc.getId())) {
            docRepository.markFailedIfPending(doc.getId(), "处理任务投递失败，请稍后重试", Instant.now());
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
