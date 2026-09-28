package io.annona.modules.knowledge.repository;

import io.annona.modules.knowledge.entity.KbDocChunkEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * kb_doc_chunk 仓储。向量列（pgvector）不经 JPA 映射（实体注释说明取舍），
 * 写入走 {@link #updateEmbedding} 原生 UPDATE：pgvector 接受 {@code "[d1,d2,…]"}
 * 字面量文本，{@code cast(? as vector)} 由 PG 解析——与 🅢 raw SQL {@code $n::vector}
 * 同一形状。embedding 调用本身在事务外（铁律），本 UPDATE 在事务内按批落库。
 */
public interface KbDocChunkRepository extends JpaRepository<KbDocChunkEntity, UUID> {

    List<KbDocChunkEntity> findByDocIdOrderByChunkIndexAsc(UUID docId);

    /**
     * 跨模块只读批量回查（P1a-08 qa 引用组装）。检索命中已按用户过滤，这里对 owner
     * 做二次校验：伪造 chunkId 拿不到他人正文（防御纵深，不信任上游调用方）。
     */
    @Query("select c from KbDocChunkEntity c join KbDocEntity d on c.docId = d.id "
        + "where d.userId = :userId and c.id in :ids")
    List<KbDocChunkEntity> findByIdsAndUserId(@Param("userId") UUID userId,
        @Param("ids") Collection<UUID> ids);

    long countByDocId(UUID docId);

    long deleteByDocId(UUID docId);

    /**
     * @param vectorLiteral pgvector 字面量（"[0.1,0.2,…]"，维度必须 1024）
     * @return 受影响行数（0 = 分块行不存在，理论上不可能，防御性返回）
     */
    @Modifying
    @Query(value = "update kb_doc_chunk set embedding = cast(:embedding as vector) where id = :id", nativeQuery = true)
    int updateEmbedding(@Param("id") UUID id, @Param("embedding") String vectorLiteral);
}
