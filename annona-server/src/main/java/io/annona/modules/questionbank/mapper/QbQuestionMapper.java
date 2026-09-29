package io.annona.modules.questionbank.mapper;

import io.annona.modules.questionbank.dto.QuestionResponse;
import io.annona.modules.questionbank.entity.QbQuestionEntity;
import java.util.UUID;
import org.mapstruct.Mapper;

/**
 * 题目 Entity → DTO（AGENTS §4：Entity 不得出服务层到 controller；mapper 独立包使
 * controller 不 import entity，ArchUnit 规则 4 同款）。
 */
@Mapper(componentModel = "spring")
public interface QbQuestionMapper {

    QuestionResponse toResponse(QbQuestionEntity question);

    /** UUID → String（id 字段）。 */
    default String mapUuid(UUID id) {
        return id == null ? null : id.toString();
    }
}
