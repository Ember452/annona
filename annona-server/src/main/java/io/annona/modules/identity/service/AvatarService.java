package io.annona.modules.identity.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.storage.ObjectStorage;
import io.annona.modules.identity.entity.AvatarChangeEntity;
import io.annona.modules.identity.entity.UserProfileEntity;
import io.annona.modules.identity.repository.AvatarChangeRepository;
import io.annona.modules.identity.repository.UserProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 头像上传 / 历史 / 回滚（P2-07；历史语义借 🅢 summer-checkin {@code api/user/avatar} 的
 * "被替换的头像记为历史、回滚即交换"，<b>传输方式不借</b>：上游 presign 浏览器直传，annona
 * 沿用自家代理上传（{@link ObjectStorage#put}，同 ResumeUploadService），免开 presign 端口）。
 *
 * <p>外部 I/O（存储）一律在事务外（AGENTS §0.3）；存储写成功后 DB 失败补偿删孤儿对象。
 * 保留最近 {@code KEEP_HISTORY} 张历史：更早的先行删对象再删行（best-effort，删失败不阻塞）。
 */
@Service
public class AvatarService {

    /** 头像大小上限（5MB，与 🅢 同值；servlet multipart 需 ≥ 本值）。 */
    public static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024;
    /** 保留历史数（回滚一步够用即可；多了是存储负担）。 */
    static final int KEEP_HISTORY = 1;

    private static final Logger LOG = LoggerFactory.getLogger(AvatarService.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final UserProfileRepository profileRepository;
    private final AvatarChangeRepository historyRepository;
    private final Optional<ObjectStorage> objectStorage;
    private final TransactionTemplate tx;

    public AvatarService(UserProfileRepository profileRepository,
                         AvatarChangeRepository historyRepository,
                         Optional<ObjectStorage> objectStorage,
                         PlatformTransactionManager transactionManager) {
        this.profileRepository = profileRepository;
        this.historyRepository = historyRepository;
        this.objectStorage = objectStorage;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 上传并启用新头像。
     *
     * <p>前置：字节非空、≤5MB、扩展名 jpg/png/webp（否则 2006/2005）；存储已装配（否则 2007）。
     * <p>副作用：新对象入存储；被替换的旧头像记为历史；超窗历史对象删除（best-effort）。
     * <p>事务：仅末段落库（写 profile + 历史 + 裁剪）在一个短事务内；存储调用在事务外。
     */
    public String upload(String userId, byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.AVATAR_TYPE_NOT_SUPPORTED, "头像内容为空");
        }
        if (content.length > MAX_AVATAR_SIZE) {
            throw new BusinessException(ErrorCode.AVATAR_TOO_LARGE);
        }
        String extension = normalizeExtension(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.AVATAR_TYPE_NOT_SUPPORTED);
        }
        ObjectStorage storage = objectStorage
            .orElseThrow(() -> new BusinessException(ErrorCode.AVATAR_STORAGE_NOT_CONFIGURED));

        UUID owner = UUID.fromString(userId);
        String storageKey = buildKey(owner, extension);
        // 存储先写（事务外）：DB 失败靠删对象补偿，不留孤儿（ResumeUploadService 同策略）
        storage.put(storageKey, content, contentTypeFor(extension));

        String previousKey;
        try {
            previousKey = tx.execute(status -> persistNewAvatar(owner, storageKey));
        } catch (RuntimeException e) {
            deleteQuietly(storage, storageKey);
            throw e;
        }
        // 超窗旧历史：先删对象再删行，best-effort（失败只留存储冗余对象，不影响主流程）
        pruneOldHistory(storage, owner);
        return previousKey;
    }

    /** 历史列表（最近优先，最多 KEEP_HISTORY 条）。 */
    public List<String> history(String userId) {
        return historyRepository.findByUserIdOrderByCreatedAtDesc(UUID.fromString(userId)).stream()
            .limit(KEEP_HISTORY)
            .map(AvatarChangeEntity::getObjectKey)
            .toList();
    }

    /**
     * 回滚到上一张头像：当前头像 ↔ 最近历史 交换。
     *
     * <p>前置：存在历史（否则 2008）。无对象删除——两 key 都仍有效，只是换当前指针。
     */
    public String rollback(String userId) {
        UUID owner = UUID.fromString(userId);
        return tx.execute(status -> {
            AvatarChangeEntity latest = historyRepository.findFirstByUserIdOrderByCreatedAtDesc(owner)
                .orElseThrow(() -> new BusinessException(ErrorCode.AVATAR_NO_HISTORY));
            UserProfileEntity profile = profileRepository.findById(owner)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_EXPIRED));
            String current = profile.getAvatarObjectKey();
            profile.setAvatarObjectKey(latest.getObjectKey());
            if (current == null) {
                historyRepository.delete(latest);
            } else {
                // 交换：历史行改写为被换下的当前 key（对象仍在，无需重传）
                latest.setObjectKey(current);
                historyRepository.save(latest);
            }
            profileRepository.save(profile);
            return profile.getAvatarObjectKey();
        });
    }

    /** 回读头像字节（代理展示，presign 未做时的替代）。key 不属于本人或未配置存储 → 2300 语义不适用，返 null 由端点转 404。 */
    public byte[] fetch(String objectKey) {
        ObjectStorage storage = objectStorage.orElse(null);
        if (storage == null || objectKey == null || !storage.exists(objectKey)) {
            return null;
        }
        return storage.get(objectKey);
    }

    /** 写 profile + 记历史，返回被替换的旧 key（供测试断言；生产返回给调用方无副作用考量）。 */
    private String persistNewAvatar(UUID owner, String newKey) {
        UserProfileEntity profile = profileRepository.findById(owner)
            .orElseGet(() -> {
                UserProfileEntity fresh = new UserProfileEntity();
                fresh.setUserId(owner);
                return fresh;
            });
        String previous = profile.getAvatarObjectKey();
        if (previous != null && !previous.equals(newKey)) {
            AvatarChangeEntity history = new AvatarChangeEntity();
            history.setId(UUID.randomUUID());
            history.setUserId(owner);
            history.setObjectKey(previous);
            historyRepository.save(history);
        }
        profile.setAvatarObjectKey(newKey);
        profileRepository.save(profile);
        return previous;
    }

    private void pruneOldHistory(ObjectStorage storage, UUID owner) {
        List<AvatarChangeEntity> all = historyRepository.findByUserIdOrderByCreatedAtDesc(owner);
        if (all.size() <= KEEP_HISTORY) {
            return;
        }
        List<AvatarChangeEntity> keep = all.subList(0, KEEP_HISTORY);
        for (AvatarChangeEntity stale : all.subList(KEEP_HISTORY, all.size())) {
            deleteQuietly(storage, stale.getObjectKey());
        }
        historyRepository.deleteByUserIdAndIdNotIn(owner, keep.stream().map(AvatarChangeEntity::getId).toList());
    }

    private void deleteQuietly(ObjectStorage storage, String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            LOG.warn("头像孤儿对象删除失败 key={} at={}", key, Instant.now(), e);
        }
    }

    private static String buildKey(UUID owner, String extension) {
        return "avatars/%s/%s.%s".formatted(owner, UUID.randomUUID(), extension);
    }

    private static String normalizeExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private static String contentTypeFor(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }
}
