package io.annona.modules.plan.mapper;

import io.annona.modules.plan.dto.PlanTaskResponse;
import io.annona.modules.plan.entity.PlanTaskEntity;
import java.util.UUID;
import org.mapstruct.Mapper;

/**
 * Entity → DTO 映射（AGENTS §4：禁止把 Entity 返回前端）；放 mapper 包使 controller
 * 不 import entity（ArchUnit 规则 4）。
 */
@Mapper(componentModel = "spring")
public interface PlanMapper {

    PlanTaskResponse toTaskResponse(PlanTaskEntity task);

    /** UUID → String（id/planId/directionId）。 */
    default String mapUuid(UUID id) {
        return id == null ? null : id.toString();
    }
}
