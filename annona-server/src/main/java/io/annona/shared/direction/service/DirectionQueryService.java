package io.annona.shared.direction.service;

import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.mapper.DirectionMapper;
import io.annona.shared.direction.repository.DirectionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 方向字典只读访问——业务模块（study / questionbank / interview…）只经本类读方向，
 * 不得触碰写侧。单查询返回「内置 + 本人」ACTIVE 方向；列表规模小（≤200+内置），不加缓存。
 */
@Service
public class DirectionQueryService {

    private final DirectionRepository repository;
    private final DirectionMapper mapper;

    public DirectionQueryService(DirectionRepository repository, DirectionMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public List<DirectionResponse> listVisible(String userId) {
        List<DirectionEntity> entities = repository.findVisibleActive(UUID.fromString(userId));
        return entities.stream().map(mapper::toResponse).toList();
    }

    /**
     * 方向可见性校验（业务模块写侧的前置闸门，direction ADR 修订 2 遗留义务）：
     * ACTIVE 且（内置或本人）。不区分“不存在 / 已归档 / 他人方向”，统一 false——
     * 调用方（study 等）据此抛 2100，不泄露他人方向的存在性。
     */
    public boolean existsVisibleTo(String userId, String directionId) {
        UUID id;
        try {
            id = UUID.fromString(directionId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
        return repository.countVisible(id, UUID.fromString(userId)) > 0;
    }
}
