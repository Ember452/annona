package io.annona.shared.direction.repository;

import io.annona.shared.direction.entity.DirectionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DirectionRepository extends JpaRepository<DirectionEntity, UUID> {

    /**
     * 调用方可见的 ACTIVE 方向：内置（user_id IS NULL）+ 本人自建，一次查询返回。
     * 内置在前、其余按名称排序（下拉分组展示）；本人半边命中 idx_direction_user partial index。
     */
    @Query("select d from DirectionEntity d where d.status = 'ACTIVE'"
        + " and (d.userId is null or d.userId = :userId)"
        + " order by case when d.userId is null then 0 else 1 end, d.name asc")
    List<DirectionEntity> findVisibleActive(@Param("userId") UUID userId);

    /** 同 owner 内 key 预检查（uq_direction_owner_key 的友好报错版；并发竞态由约束兜底）。 */
    boolean existsByUserIdAndKey(UUID userId, String key);

    /** 同 owner 内按 name 判重（ACTIVE 范围）：中文等非 ASCII 名称的 key 是随机段，唯一约束拦不住同名不同 key。 */
    boolean existsByUserIdAndNameAndStatus(UUID userId, String name, String status);

    /** 自定义方向上限（ADR §后果 2：单用户 USER_CUSTOM ≤ 200）的计数。 */
    long countByUserIdAndOriginAndStatus(UUID userId, String origin, String status);

    /** owner 范围内取方向：查不到即 2100，不泄露内置/他人方向的存在性。 */
    Optional<DirectionEntity> findByIdAndUserId(UUID id, UUID userId);
}
