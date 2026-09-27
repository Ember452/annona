package io.annona.modules.knowledge.ingest;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.storage.ObjectStorage;
import io.annona.modules.knowledge.chunk.Chunker;
import io.annona.modules.knowledge.dto.UploadResponse;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.listener.KnowledgeVectorizeStream;
import io.annona.modules.knowledge.repository.KbDocRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 上传编排（knowledge-ingestion-adr §决策 3/7；七步形状借 🅖 KnowledgeBaseUploadService）：
 * 校验 → 方向归属 → hash 幂等 → S3 → 落库（PENDING）→ 流投递 → 强类型响应。
 *
 * <p>事务与补偿：请求线程<b>不解析正文</b>（正文由消费者下载后解析）；DB 写失败时
 * 补偿删除刚上传的 S3 孤儿对象（借 🅖 compensateOrphanObject，补偿失败不掩盖原异常）；
 * 投递失败不回滚——文档保留在库，标记 FAILED 提示可重试处理。所有外部调用
 * （S3）在事务外（铁律）。
 */
@Service
public class KnowledgeUploadService {

    /** 上传大小上限（借 🅖 MAX_FILE_SIZE；servlet multipart 上限同值，application.yaml）。 */
    public static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    /** 支持的扩展名白名单（md 讲义与 pdf/docx 教材；MIME 嗅探 v1 不做，解析失败有兜底报错）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md", "markdown", "mdown", "txt");

    private static final int STORAGE_KEY_UUID_PREFIX = 8;

    private final KbDocRepository docRepository;
    private final Optional<ObjectStorage> objectStorage;
    private final KnowledgeVectorizeStream vectorizeStream;
    private final io.annona.shared.direction.service.DirectionQueryService directionQueryService;

    public KnowledgeUploadService(KbDocRepository docRepository,
        Optional<ObjectStorage> objectStorage,
        KnowledgeVectorizeStream vectorizeStream,
        io.annona.shared.direction.service.DirectionQueryService directionQueryService) {
        this.docRepository = docRepository;
        this.objectStorage = objectStorage;
        this.vectorizeStream = vectorizeStream;
        this.directionQueryService = directionQueryService;
    }

    /**
     * @param userId      上传者（当前登录用户，SPI Principal.id() 字符串口径）
     * @param content     文件字节
     * @param filename    原始文件名（决定扩展名校验与展示名）
     * @param directionId 上传时必选方向（direction ADR）
     * @throws BusinessException 2301 超限 / 2302 类型不支持 / 2100 方向不可见 /
     *                         2306 存储未配置 / 2307 投递失败
     */
    public UploadResponse upload(String userId, byte[] content, String filename, String directionId) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.KB_DOC_TEXT_EMPTY, "上传文件为空");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.KB_DOC_TOO_LARGE);
        }
        String extension = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.KB_DOC_TYPE_NOT_SUPPORTED);
        }
        UUID user = parseUuid(userId);
        UUID direction = parseDirection(directionId);
        if (!directionQueryService.existsVisibleTo(userId, directionId)) {
            throw new BusinessException(ErrorCode.DIRECTION_NOT_FOUND);
        }

        // hash 幂等：同用户重复上传秒级返回已有文档，零解析零 token（ADR §决策 7 后果）
        String fileHash = ContentHashes.sha256Hex(content);
        Optional<KbDocEntity> existing = docRepository.findByUserIdAndFileHash(user, fileHash);
        if (existing.isPresent()) {
            KbDocEntity doc = existing.get();
            return new UploadResponse(doc.getId(), true, doc.getStatus(),
                "已在库中，本次未产生任何解析与向量消耗");
        }

        ObjectStorage storage = objectStorage
            .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOC_STORAGE_NOT_CONFIGURED));
        String storageKey = buildStorageKey(filename);
        storage.put(storageKey, content, contentTypeFor(extension));

        KbDocEntity doc = new KbDocEntity();
        doc.setId(UUID.randomUUID());
        doc.setUserId(user);
        doc.setDirectionId(direction);
        doc.setFileHash(fileHash);
        doc.setName(displayName(filename));
        doc.setOriginalFilename(filename);
        doc.setFileSize(content.length);
        doc.setContentType(contentTypeFor(extension));
        doc.setStorageKey(storageKey);
        doc.setStatus(KbDocEntity.STATUS_PENDING);
        doc.setAnalyzerVersion(Chunker.VERSION);
        doc.setUpdatedAt(Instant.now());
        try {
            docRepository.save(doc);
        } catch (RuntimeException e) {
            storage.delete(storageKey); // 补偿：不留 S3 孤儿对象；补偿失败不掩盖原异常（借 🅖）
            throw e;
        }

        if (!vectorizeStream.send(doc.getId())) {
            docRepository.markFailedIfPending(doc.getId(), "处理任务投递失败，请在文档列表中重试处理",
                Instant.now());
            throw new BusinessException(ErrorCode.KB_DOC_ENQUEUE_FAILED);
        }
        return new UploadResponse(doc.getId(), false, KbDocEntity.STATUS_PENDING, "已上传，解析与向量化进行中");
    }

    private UUID parseDirection(String directionId) {
        return parseUuid(directionId);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "UUID 格式不合法");
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private static String displayName(String filename) {
        if (filename == null) {
            return "未命名文档";
        }
        int dot = filename.lastIndexOf('.');
        String base = dot <= 0 ? filename : filename.substring(0, dot);
        return base.isBlank() ? "未命名文档" : base;
    }

    /** {@code knowledge/{yyyy/MM/dd}/{uuid8}_{安全文件名}}（借 🅖 布局；中文不做拼音转换，
     * 非 ASCII 直接替换为下划线——展示名走 DB 字段，key 只需唯一与文件系统安全）。 */
    private static String buildStorageKey(String filename) {
        String safe = (filename == null ? "unnamed" : filename)
            .replaceAll("[^A-Za-z0-9._-]", "_");
        String prefix = UUID.randomUUID().toString().substring(0, STORAGE_KEY_UUID_PREFIX);
        return "knowledge/%s/%s_%s".formatted(LocalDate.now(), prefix, safe);
    }

    private static String contentTypeFor(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            default -> "text/markdown";
        };
    }
}
