package io.annona.modules.knowledge.mapper;

import io.annona.modules.knowledge.dto.KbChunkReference;
import io.annona.modules.knowledge.dto.KbDocChunkView;
import io.annona.modules.knowledge.dto.KbDocSummaryResponse;
import io.annona.modules.knowledge.entity.KbDocChunkEntity;
import io.annona.modules.knowledge.entity.KbDocEntity;
import org.mapstruct.Mapper;

/**
 * knowledge 模块 Entity→DTO 映射（AGENTS §4 全仓 MapStruct 约定；record 目标用
 * MapStruct 1.6 的构造器绑定）。详情组合（摘要 + 分块列表）在服务层用本 mapper 逐项装配。
 */
@Mapper(componentModel = "spring")
public interface KnowledgeMapper {

    KbDocSummaryResponse toSummary(KbDocEntity entity);

    KbDocChunkView toChunkView(KbDocChunkEntity entity);

    KbChunkReference toChunkReference(KbDocChunkEntity entity);
}
