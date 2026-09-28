package io.annona.modules.qa.mapper;

import io.annona.modules.qa.dto.QaMessageResponse;
import io.annona.modules.qa.dto.QaSessionResponse;
import io.annona.modules.qa.entity.QaMessageEntity;
import io.annona.modules.qa.entity.QaSessionEntity;
import org.mapstruct.Mapper;

/**
 * qa 模块 Entity→DTO 映射（AGENTS §4 全仓 MapStruct 约定；record 目标用构造器绑定）。
 */
@Mapper(componentModel = "spring")
public interface QaMapper {

    QaSessionResponse toSessionResponse(QaSessionEntity entity);

    QaMessageResponse toMessageResponse(QaMessageEntity entity);
}
