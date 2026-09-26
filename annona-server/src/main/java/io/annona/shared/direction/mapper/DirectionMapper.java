package io.annona.shared.direction.mapper;

import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.entity.DirectionEntity;
import java.util.UUID;
import org.mapstruct.Mapper;

/**
 * Entity → DTO 映射（AGENTS.md §4：禁止把 Entity 返回前端）。
 * 放在 {@code mapper} 包而非 {@code controller}，使 controller 不 import entity（ArchUnit 规则 4）。
 */
@Mapper(componentModel = "spring")
public interface DirectionMapper {

    DirectionResponse toResponse(DirectionEntity direction);

    /** UUID → String（MapStruct 自动用于 id / kbDocId 字段）。 */
    default String mapUuid(UUID id) {
        return id == null ? null : id.toString();
    }
}
