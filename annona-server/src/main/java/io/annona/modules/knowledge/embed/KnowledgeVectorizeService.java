package io.annona.modules.knowledge.embed;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.parse.DocumentBlock;
import io.annona.common.parse.DocumentParser;
import io.annona.common.search.Tokenizer;
import io.annona.common.search.VectorLiterals;
import io.annona.common.storage.ObjectStorage;
import io.annona.common.stream.TaskStreamPort;
import io.annona.common.support.ContentHashes;
import io.annona.common.usage.UsageLedger;
import io.annona.modules.knowledge.chunk.ChunkOptions;
import io.annona.modules.knowledge.chunk.Chunker;
import io.annona.modules.knowledge.chunk.KnowledgeChunk;
import io.annona.shared.progress.ProgressEvent;
import io.annona.modules.knowledge.entity.KbDocChunkEntity;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.shared.progress.SseProgressHub;
import io.annona.modules.knowledge.repository.KbDocChunkRepository;
import io.annona.modules.knowledge.repository.KbDocRepository;
import io.annona.spi.dto.EmbeddingResult;
import io.annona.spi.model.EmbeddingProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 向量化消费业务（任务流的 {@link TaskStreamPort.TaskMessageHandler}）。
 * 状态机推进（knowledge-ingestion-adr §决策 3）与并发语义借 🅖 VectorizeStreamConsumer：
 * 条件领取（PENDING→PARSING 抢到才干）→ 下载解析 → 分块落库 → 批量嵌入（进度/心跳 30s
 * 节流）→ 条件终态；代次 fencing（attempt_id）保证被回收的旧代次写不进任何状态。
 *
 * <p>失败语义（借 🅖 模板）：一切异常先按可重试处理——把文档条件重置回 PENDING 再
 * RETRY（重投后重新领取）；重试计数达上限（{@link #MAX_RETRY}，借 🅖）或重置失败时
 * 条件判 FAILED 并 DEAD。解析类确定性失败也被重试（与上游一致，代价是三次重复解析，
 * 换取单一失败路径的简单性）。
 */
@Service
public class KnowledgeVectorizeService implements TaskStreamPort.TaskMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorizeService.class);

    /** 重试上限（借 🅖 MAX_RETRY_COUNT；重投由端口承担 retryCount 计数）。 */
    public static final int MAX_RETRY = 3;

    /** 进度推进粒度（每批一条 SSE + 一次 DB 进度写）；与 provider 内部的分批上限无关。 */
    private static final int PROGRESS_BATCH_SIZE = 10;

    /** 心跳节流（借 🅖 heartbeatThrottle=30s：批与批之间最多 30s 写一次库）。 */
    private static final long HEARTBEAT_THROTTLE_MILLIS = 30_000;

    /** 消费者丢失执行权（代次被回收/他人接手）的静默中止信号：ACK 丢弃，不重试不判死。 */
    static final class AttemptLostException extends RuntimeException {
    }

    private final KbDocRepository docRepository;
    private final KbDocChunkRepository chunkRepository;
    private final Optional<ObjectStorage> objectStorage;
    private final DocumentParser documentParser;
    /**
     * 应用层中文分词端口（P1a-07）。不包 Optional：分词是本地纯计算、无外部配置，任何
     * 环境下都存在；写侧与读侧必须用同一个 bean（Tokenizer javadoc 的不变量）。
     */
    private final Tokenizer tokenizer;
    private final Optional<EmbeddingProvider> embeddingProvider;
    private final SseProgressHub progressHub;
    /**
     * 向量化用量记账（metering-adr 批 3 修订，决策 4 的 embed 债）：经 common 端口注入，
     * 不直依赖 modules/usage（跨模块写）。
     */
    private final UsageLedger usageLedger;
    /**
     * 条件 UPDATE 的事务来源：每个状态迁移经 {@link TransactionTemplate} 自成一个短事务
     * （LoginAttemptStore 先例）。@Modifying 查询没有调用方事务时 Hibernate 会抛
     * TransactionRequiredException，而后台消费线程没有任何现成事务上下文——切片测试
     * mock 掉仓储探不到，只有真库能暴露（P1a-05 CI 实测教训）。
     */
    private final TransactionTemplate tx;

    public KnowledgeVectorizeService(KbDocRepository docRepository,
        KbDocChunkRepository chunkRepository,
        Optional<ObjectStorage> objectStorage,
        DocumentParser documentParser,
        Tokenizer tokenizer,
        Optional<EmbeddingProvider> embeddingProvider,
        SseProgressHub progressHub,
        UsageLedger usageLedger,
        PlatformTransactionManager transactionManager) {
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.objectStorage = objectStorage;
        this.documentParser = documentParser;
        this.tokenizer = tokenizer;
        this.embeddingProvider = embeddingProvider;
        this.progressHub = progressHub;
        this.usageLedger = usageLedger;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public TaskStreamPort.Outcome handle(String msgId, Map<String, String> payload, int retryCount) {
        String docIdRaw = payload.get("docId");
        if (docIdRaw == null || docIdRaw.isBlank()) {
            return TaskStreamPort.Outcome.ACK; // 消息缺业务 ID（借 🅖：丢弃不处理）
        }
        UUID docId;
        try {
            docId = UUID.fromString(docIdRaw);
        } catch (IllegalArgumentException e) {
            return TaskStreamPort.Outcome.ACK;
        }
        Optional<KbDocEntity> found = docRepository.findById(docId);
        if (found.isEmpty()) {
            return TaskStreamPort.Outcome.ACK; // 实体不存在（已删）→ ACK 丢弃
        }
        KbDocEntity doc = found.get();
        if (KbDocEntity.STATUS_READY.equals(doc.getStatus())) {
            return TaskStreamPort.Outcome.ACK; // 已终态（重复投递）→ ACK 丢弃
        }

        String attemptId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Integer claimed = tx.execute(status -> docRepository.tryMarkParsing(docId, attemptId, now));
        if (claimed == null || claimed == 0) {
            return TaskStreamPort.Outcome.ACK; // 条件领取失败：其他实例已接手（借 🅖 tryMarkVectorProcessing）
        }
        try {
            process(doc, attemptId);
            return TaskStreamPort.Outcome.ACK;
        } catch (AttemptLostException e) {
            return TaskStreamPort.Outcome.ACK; // 执行权已失效：不重试不判死（新代次已接管）
        } catch (RuntimeException e) {
            log.warn("向量化失败 docId={} retryCount={}", docId, retryCount, e);
            return onFailure(docId, attemptId, retryCount, rootMessage(e), now);
        }
    }

    private void process(KbDocEntity doc, String attemptId) {
        UUID docId = doc.getId();
        ObjectStorage storage = objectStorage
            .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOC_STORAGE_NOT_CONFIGURED));
        long[] lastHeartbeat = {0L};

        progressHub.publish(docId, new ProgressEvent(KbDocEntity.STATUS_PARSING, "解析中", 0, 0, ""));
        byte[] content = storage.get(doc.getStorageKey());
        List<DocumentBlock> blocks = documentParser.parse(content, doc.getOriginalFilename());
        if (blocks.isEmpty()) {
            throw new BusinessException(ErrorCode.KB_DOC_TEXT_EMPTY);
        }

        beat(docId, attemptId, lastHeartbeat);
        if (tx.execute(s -> docRepository.tryMarkChunking(docId, attemptId, Instant.now())) == 0) {
            throw new AttemptLostException();
        }
        progressHub.publish(docId, new ProgressEvent(KbDocEntity.STATUS_CHUNKING, "分块中", 0, 0, ""));
        List<KnowledgeChunk> chunks = Chunker.chunk(blocks, ChunkOptions.DEFAULTS);
        if (chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.KB_DOC_TEXT_EMPTY);
        }
        persistChunks(docId, chunks);
        Map<Integer, UUID> chunkIdByIndex = new HashMap<>();
        for (KbDocChunkEntity row : chunkRepository.findByDocIdOrderByChunkIndexAsc(docId)) {
            chunkIdByIndex.put(row.getChunkIndex(), row.getId());
        }

        if (tx.execute(s -> docRepository.tryMarkEmbedding(docId, attemptId, chunks.size(),
            Instant.now())) == 0) {
            throw new AttemptLostException();
        }
        EmbeddingProvider provider = embeddingProvider
            .orElseThrow(() -> new BusinessException(ErrorCode.KB_EMBEDDING_NOT_CONFIGURED));
        progressHub.publish(docId, new ProgressEvent(KbDocEntity.STATUS_EMBEDDING, "向量化中",
            0, chunks.size(), ""));

        int processed = 0;
        int embedPrompt = 0; // 跨批累加的输入 token（一次向量化只记一行账，不逐批刷）
        // 进度推进粒度（每批发一条 SSE + 一次 DB 进度写），与 provider 内部的分批上限无关
        for (int from = 0; from < chunks.size(); from += PROGRESS_BATCH_SIZE) {
            beat(docId, attemptId, lastHeartbeat);
            List<KnowledgeChunk> batch = chunks.subList(from,
                Math.min(from + PROGRESS_BATCH_SIZE, chunks.size()));
            EmbeddingResult result = provider.embed(batch.stream().map(KnowledgeChunk::text).toList());
            List<float[]> vectors = result.vectors();
            embedPrompt += result.usage().promptTokens();
            // 整批的向量写 + 进度写合进一个短事务（TD-02：旧写法逐 chunk 一事务，大文档
            // = 数千小事务）；行在事务外算齐、事务内只写已知 id，embed 仍在事务外（铁律）；
            // chunkIdByIndex 来自 persistChunks 后回读，重跑时同 id 重写（幂等）
            List<UUID> batchChunkIds = new ArrayList<>(batch.size());
            List<String> batchVectors = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                UUID chunkId = chunkIdByIndex.get(batch.get(i).index());
                if (chunkId == null) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "分块行缺失 docId=" + docId + " index=" + batch.get(i).index());
                }
                batchChunkIds.add(chunkId);
                batchVectors.add(VectorLiterals.of(vectors.get(i)));
            }
            processed += batch.size();
            int processedNow = processed;
            tx.executeWithoutResult(s -> {
                for (int i = 0; i < batchChunkIds.size(); i++) {
                    chunkRepository.updateEmbedding(batchChunkIds.get(i), batchVectors.get(i));
                }
                docRepository.markProgress(docId, attemptId, processedNow, Instant.now());
            });
            progressHub.publish(docId, new ProgressEvent(KbDocEntity.STATUS_EMBEDDING, "向量化中",
                processed, chunks.size(), ""));
        }

        if (tx.execute(s -> docRepository.markReady(docId, attemptId, chunks.size(),
            provider.name(), Instant.now())) == 0) {
            throw new AttemptLostException();
        }
        // 向量化用量补票（scene KB_INGEST，V12 扩 CHECK）：整个文档 embed 累计账一行；
        // provider = 通道、model = 向量身份（两列语义独立，TD-03）；total=0（fake/不回填）不记。
        if (embedPrompt > 0) {
            usageLedger.record(new UsageLedger.UsageEntry(doc.getUserId(), "KB_INGEST", docId,
                provider.channel(), provider.name(), "embedding", embedPrompt, 0, null, null));
        }
        progressHub.publish(docId, new ProgressEvent(KbDocEntity.STATUS_READY, "就绪",
            chunks.size(), chunks.size(), ""));
    }

    /**
     * 分块落库（重建式：先清掉旧分块再写新分块，ADR §决策 7）。delete 与 saveAll 在
     * 同一个短事务里原子完成（原来分成两个方法级事务、靠幂等重跑兜底——有了
     * TransactionTemplate 就不必再留中间态）。embedding 调用保持在事务外（铁律）。
     * heading_path 落库前截断到列宽（畸形多级长标题 otherwise 撑爆 VARCHAR(512)，
     * 文档会以一个难懂的 DB 错误 FAILED）。
     *
     * <p>{@code tokens} 在本方法里随 content 一起写（V5 的 tsv 生成列从它派生）：分词与
     * 分块同址内联执行，没放到 CPU 池——keyword ADR 修订第 4 条登记了这个偏离及其
     * 重新评估条件。分词失败不单独报错：它是纯本地计算，报错宁叫整次处理重试
     * 而不是写出一批无分词的行（那会静默抬高关键词通道漏召）。
     */
    private void persistChunks(UUID docId, List<KnowledgeChunk> chunks) {
        List<KbDocChunkEntity> rows = new ArrayList<>(chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            KbDocChunkEntity row = new KbDocChunkEntity();
            row.setId(UUID.randomUUID());
            row.setDocId(docId);
            row.setChunkIndex(chunk.index());
            row.setHeadingPath(chunk.headingPath().length() > 512
                ? chunk.headingPath().substring(0, 512)
                : chunk.headingPath());
            row.setCharStart(chunk.charStart());
            row.setCharEnd(chunk.charEnd());
            row.setContent(chunk.text());
            row.setContentHash(ContentHashes.sha256Hex(chunk.text()));
            row.setTokens(tokenizer.tokenize(chunk.text()));
            row.setTokenizerVersion(tokenizer.version());
            rows.add(row);
        }
        tx.executeWithoutResult(status -> {
            chunkRepository.deleteByDocId(docId);
            chunkRepository.saveAll(rows);
        });
    }

    /** 可重试失败：条件重置回 PENDING 后重投；达上限或重置失败（代次易主）→ 判死。 */
    private TaskStreamPort.Outcome onFailure(UUID docId, String attemptId, int retryCount,
        String message, Instant now) {
        progressHub.publish(docId, new ProgressEvent(KbDocEntity.STATUS_FAILED, "失败",
            0, 0, message));
        Integer reset = tx.execute(s -> docRepository.resetStaleToPending(docId, attemptId, now));
        if (reset != null && reset == 1 && retryCount < MAX_RETRY) {
            return TaskStreamPort.Outcome.RETRY;
        }
        tx.executeWithoutResult(s -> docRepository.markFailedIfPending(docId, message, now));
        return TaskStreamPort.Outcome.DEAD;
    }

    /** 心跳（30s 节流，借 🅖 throttledEmbeddingHeartbeat）；心跳 0 行 = 执行权已失效。 */
    private void beat(UUID docId, String attemptId, long[] lastHeartbeat) {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat[0] < HEARTBEAT_THROTTLE_MILLIS) {
            return;
        }
        Integer alive = tx.execute(s -> docRepository.heartbeat(docId, attemptId, Instant.now()));
        if (alive == null || alive == 0) {
            throw new AttemptLostException();
        }
        lastHeartbeat[0] = now;
    }

    private static String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
