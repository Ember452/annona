package io.annona.modules.study.mapper;

import io.annona.modules.study.dto.CheckinResponse;
import io.annona.modules.study.dto.SessionResponse;
import io.annona.modules.study.entity.CheckinEntity;
import io.annona.modules.study.entity.StudySessionEntity;
import java.util.UUID;
import org.mapstruct.Mapper;

/**
 * Entity → DTO 映射（AGENTS.md §4：禁止把 Entity 返回前端）。
 * 放在 {@code mapper} 包而非 {@code controller}，使 controller 不 import entity（ArchUnit 规则 4）。
 */
@Mapper(componentModel = "spring")
public interface StudyMapper {

    SessionResponse toResponse(StudySessionEntity session);

    CheckinResponse toResponse(CheckinEntity checkin);

    /** UUID → String（MapStruct 自动用于 id / directionId / checkinId 字段）。 */
    default String mapUuid(UUID id) {
        return id == null ? null : id.toString();
    }
}
