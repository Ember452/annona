package io.annona.modules.knowledge.ops;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.knowledge.chunk.Chunker;
import io.annona.modules.knowledge.dto.KbChunkReference;
import io.annona.modules.knowledge.dto.KbDocChunkView;
import io.annona.modules.knowledge.dto.KbDocDetailResponse;
import io.annona.modules.knowledge.dto.KbDocStatusResponse;
import io.annona.modules.knowledge.dto.KbDocSummaryResponse;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.mapper.KnowledgeMapper;
import io.annona.modules.knowledge.repository.KbDocChunkRepository;
import io.annona.modules.knowledge.repository.KbDocRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档读侧（列表 / 详情含分块预览 / 状态快照）。owner 范围内取文档，
 * 查不到（含 id 格式非法——按不存在处理不泄露有效性）即 2300，与 study/direction 同口径。
 */
@Service
public class KnowledgeDocQueryService {

    private final KbDocRepository docRepository;
    private final KbDocChunkRepository chunkRepository;
    private final KnowledgeMapper mapper;

    public KnowledgeDocQueryService(KbDocRepository docRepository,
        KbDocChunkRepository chunkRepository, KnowledgeMapper mapper) {
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.mapper = mapper;
    }

    public List<KbDocSummaryResponse> list(String userId) {
        return docRepository.findAllByUserIdOrderByCreatedAtDesc(uuid(userId)).stream()
            .map(mapper::toSummary)
            .toList();
    }

    @Transactional(readOnly = true)
    public KbDocDetailResponse detail(String userId, String docId) {
        KbDocEntity doc = owned(userId, docId);
        List<KbDocChunkView> chunks = chunkRepository
            .findByDocIdOrderByChunkIndexAsc(doc.getId()).stream()
            .map(mapper::toChunkView)
            .toList();
        return new KbDocDetailResponse(mapper.toSummary(doc), Chunker.VERSION, chunks);
    }

    /**
     * 分块跨模块只读批量回查（P1a-08：qa 引用组装）。入参 id 解析失败/查不到的静默跳过
     * （命中到回查之间文档可能被删除，引用缺一条比报错合理）；owner 过滤在 SQL 层。
     */
    public List<KbChunkReference> chunkReferences(String userId, List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = chunkIds.stream()
            .map(KnowledgeDocQueryService::optionalUuid)
            .flatMap(Optional::stream)
            .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return chunkRepository.findByIdsAndUserId(uuid(userId), ids).stream()
            .map(mapper::toChunkReference)
            .toList();
    }

    public KbDocStatusResponse status(String userId, String docId) {
        KbDocEntity doc = owned(userId, docId);
        return new KbDocStatusResponse(doc.getStatus(), stageOf(doc), doc.getProcessedChunks(),
            doc.getTotalChunks(), doc.getError() == null ? "" : doc.getError());
    }

    private KbDocEntity owned(String userId, String docId) {
        Optional<UUID> id = optionalUuid(docId);
        if (id.isEmpty()) {
            throw new BusinessException(ErrorCode.KB_DOC_NOT_FOUND);
        }
        return docRepository.findByIdAndUserId(id.get(), uuid(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOC_NOT_FOUND));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static Optional<UUID> optionalUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String stageOf(KbDocEntity doc) {
        return switch (doc.getStatus()) {
            case KbDocEntity.STATUS_PENDING -> "排队中";
            case KbDocEntity.STATUS_PARSING -> "解析中";
            case KbDocEntity.STATUS_CHUNKING -> "分块中";
            case KbDocEntity.STATUS_EMBEDDING -> "向量化中";
            case KbDocEntity.STATUS_READY -> "就绪";
            default -> "失败";
        };
    }
}
