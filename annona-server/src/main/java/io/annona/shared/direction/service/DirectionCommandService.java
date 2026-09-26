package io.annona.shared.direction.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.shared.direction.dto.CreateDirectionRequest;
import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.mapper.DirectionMapper;
import io.annona.shared.direction.repository.DirectionRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 方向字典写侧：新建（USER_CUSTOM 即时落库）、归档（唯一的"删除"路径，物理删端点
 * 永不存在——direction-master-data-adr §后果 3）、绑定知识库（USER_CUSTOM 单向升级
 * 为 KNOWLEDGE_BASE，P1a-05 接入真实 kb_doc 表后再议解绑）。
 */
@Service
public class DirectionCommandService {

    /** 单用户自定义方向上限（ADR §后果 2）；超出提示归档/合并。 */
    private static final int MAX_USER_CUSTOM = 200;
    private static final int MAX_NAME_LENGTH = 128;

    private final DirectionRepository repository;
    private final DirectionMapper mapper;
    private final EntityManager entityManager;

    public DirectionCommandService(DirectionRepository repository, DirectionMapper mapper,
                                   EntityManager entityManager) {
        this.repository = repository;
        this.mapper = mapper;
        this.entityManager = entityManager;
    }

    @Transactional
    public DirectionResponse create(String userId, CreateDirectionRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "方向名称需为 1–128 个字符");
        }
        UUID owner = UUID.fromString(userId);
        // 中文等非 ASCII 名称的 key 是随机段，uq_direction_owner_key 拦不住“同名不同 key”
        // ——同名重复必须在 name 维度拦截，否则下拉会出现两个「刑法学」（数据被切两半）
        if (repository.existsByUserIdAndNameAndStatus(
                owner, name, DirectionEntity.STATUS_ACTIVE)) {
            throw new BusinessException(ErrorCode.DIRECTION_KEY_DUPLICATE,
                "同名方向已存在：" + name);
        }
        if (repository.countByUserIdAndOriginAndStatus(
                owner, DirectionEntity.ORIGIN_USER_CUSTOM, DirectionEntity.STATUS_ACTIVE)
            >= MAX_USER_CUSTOM) {
            throw new BusinessException(ErrorCode.DIRECTION_LIMIT_REACHED);
        }
        String key = generateKey(name, request.key());
        if (repository.existsByUserIdAndKey(owner, key)) {
            throw new BusinessException(ErrorCode.DIRECTION_KEY_DUPLICATE,
                "该标识已被占用：" + key);
        }
        DirectionEntity entity = new DirectionEntity();
        entity.setId(UUID.randomUUID());
        entity.setKey(key);
        entity.setName(name);
        entity.setOrigin(DirectionEntity.ORIGIN_USER_CUSTOM);
        entity.setStatus(DirectionEntity.STATUS_ACTIVE);
        entity.setUserId(owner);
        repository.saveAndFlush(entity);
        // created_at 是 insertable=false + DB DEFAULT：flush 后 refresh 回读，保证 POST 响应
        // 与 GET 同形（createdAt 非 null）。并发窗口穿过预检查撞唯一约束时，DIVE 从这里穿出
        // 事务边界（事务内 catch 不可行），由 GlobalExceptionHandler 转成 409/DATA_CONFLICT
        entityManager.refresh(entity);
        return mapper.toResponse(entity);
    }

    @Transactional
    public void archive(String userId, String directionId) {
        DirectionEntity entity = findOwned(userId, directionId);
        // 重复归档幂等：status 本就是 ARCHIVED 时写回同值，不报错
        entity.setStatus(DirectionEntity.STATUS_ARCHIVED);
        repository.save(entity);
    }

    @Transactional
    public DirectionResponse bindKbDoc(String userId, String directionId, String kbDocId) {
        UUID docId = parseUuid(kbDocId);
        DirectionEntity entity = findOwned(userId, directionId);
        if (!DirectionEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "方向已归档，无法绑定知识库");
        }
        entity.setKbDocId(docId);
        entity.setOrigin(DirectionEntity.ORIGIN_KNOWLEDGE_BASE);
        return mapper.toResponse(repository.save(entity));
    }

    /** 显式 key 非法是用户输入问题 → 业务错误码 1001，而非 500。 */
    private String generateKey(String name, String providedKey) {
        try {
            return DirectionKeys.generate(name, providedKey);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * owner 范围内取方向（含已归档，归档态绑定要在其上拒绝而非误报不存在）：
     * 查不到即 2100，不泄露内置/他人方向的存在性。
     */
    private DirectionEntity findOwned(String userId, String directionId) {
        UUID id = parseUuid(directionId);
        return repository.findByIdAndUserId(id, UUID.fromString(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.DIRECTION_NOT_FOUND));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "需为合法 UUID，实际收到：" + value);
        }
    }
}
