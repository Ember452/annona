package io.annona.modules.resume.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.storage.ObjectStorage;
import io.annona.common.stream.TaskStreamPort;
import io.annona.common.support.ContentHashes;
import io.annona.modules.resume.dto.ResumeUploadResponse;
import io.annona.modules.resume.entity.ResumeEntity;
import io.annona.modules.resume.listener.ResumeStream;
import io.annona.modules.resume.repository.ResumeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 简历上传编排（P1b-08，形状复用 {@code KnowledgeUploadService}）：校验 → hash 幂等 → 存储 →
 * 落库（PENDING）→ 流投递 → 响应。DB 写失败补偿删存储孤儿对象；投递失败判 FAILED 提示重试；
 * 所有外部 I/O（存储/流）在事务外（铁律）。生产直连 TaskStreamPort（与消费门控分离）。
 */
@Service
public class ResumeUploadService {

    /** 简历大小上限（远小于讲义，20MB 足够；servlet multipart 同值）。 */
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    /** 分析器版本（改 prompt 递增，与 evaluator_version 同思想）。 */
    public static final String ANALYZER_VERSION = "v1";

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md", "markdown");
    private static final int STORAGE_KEY_UUID_PREFIX = 8;

    private final ResumeRepository resumeRepository;
    private final Optional<ObjectStorage> objectStorage;
    private final TaskStreamPort taskStreamPort;
    private final TransactionTemplate tx;

    public ResumeUploadService(ResumeRepository resumeRepository,
                               Optional<ObjectStorage> objectStorage,
                               TaskStreamPort taskStreamPort,
                               PlatformTransactionManager transactionManager) {
        this.resumeRepository = resumeRepository;
        this.objectStorage = objectStorage;
        this.taskStreamPort = taskStreamPort;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * @throws BusinessException 3101 超限 / 3102 类型不支持 / 3103 存储未配置 / 3104 投递失败
     */
    public ResumeUploadResponse upload(String userId, byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.RESUME_TYPE_NOT_SUPPORTED, "上传文件为空");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.RESUME_TOO_LARGE);
        }
        String extension = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.RESUME_TYPE_NOT_SUPPORTED);
        }
        UUID user = parseUuid(userId);
        String fileHash = ContentHashes.sha256Hex(content);
        Optional<ResumeEntity> existing = resumeRepository.findByUserIdAndFileHash(user, fileHash);
        if (existing.isPresent()) {
            ResumeEntity doc = existing.get();
            return new ResumeUploadResponse(doc.getId(), true, doc.getStatus(),
                "已解析过同一份简历，未重复上传与分析");
        }

        ObjectStorage storage = objectStorage
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_STORAGE_NOT_CONFIGURED));
        String storageKey = buildStorageKey(filename);
        storage.put(storageKey, content, contentTypeFor(extension));

        ResumeEntity resume = new ResumeEntity();
        resume.setId(UUID.randomUUID());
        resume.setUserId(user);
        resume.setName(displayName(filename));
        resume.setOriginalFilename(filename);
        resume.setStorageKey(storageKey);
        resume.setFileSize(content.length);
        resume.setContentType(contentTypeFor(extension));
        resume.setFileHash(fileHash);
        resume.setStatus(ResumeEntity.STATUS_PENDING);
        resume.setAnalyzerVersion(ANALYZER_VERSION);
        resume.setUpdatedAt(Instant.now());
        try {
            resumeRepository.save(resume);
        } catch (DataIntegrityViolationException e) {
            // 并发同内容上传撞 (user_id, file_hash)：按幂等返回赢家，不留孤儿对象
            ResumeEntity winner = resumeRepository.findByUserIdAndFileHash(user, fileHash).orElse(null);
            if (winner != null) {
                storage.delete(storageKey);
                return new ResumeUploadResponse(winner.getId(), true, winner.getStatus(),
                    "已解析过同一份简历，未重复上传与分析");
            }
            throw e;
        } catch (RuntimeException e) {
            storage.delete(storageKey); // 补偿：不留存储孤儿对象；补偿失败不掩盖原异常
            throw e;
        }

        if (!taskStreamPort.send(ResumeStream.STREAM_KEY,
            Map.of("resumeId", resume.getId().toString()))) {
            tx.executeWithoutResult(s -> resumeRepository.markFailed(resume.getId(),
                "分析任务投递失败，请稍后重试", Instant.now()));
            throw new BusinessException(ErrorCode.RESUME_ENQUEUE_FAILED);
        }
        return new ResumeUploadResponse(resume.getId(), false, ResumeEntity.STATUS_PENDING,
            "已上传，AI 分析进行中");
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
            return "未命名简历";
        }
        int dot = filename.lastIndexOf('.');
        String base = dot <= 0 ? filename : filename.substring(0, dot);
        return base.isBlank() ? "未命名简历" : base;
    }

    private static String buildStorageKey(String filename) {
        String safe = (filename == null ? "resume" : filename).replaceAll("[^A-Za-z0-9._-]", "_");
        String prefix = UUID.randomUUID().toString().substring(0, STORAGE_KEY_UUID_PREFIX);
        return "resume/%s/%s_%s".formatted(LocalDate.now(), prefix, safe);
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
