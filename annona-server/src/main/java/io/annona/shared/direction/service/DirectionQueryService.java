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
}
