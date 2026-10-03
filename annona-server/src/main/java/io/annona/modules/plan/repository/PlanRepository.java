package io.annona.modules.plan.repository;

import io.annona.modules.plan.entity.PlanEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<PlanEntity, UUID> {

    /** 计划列表（最近更新倒序）。 */
    List<PlanEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    /** owner 范围内取计划：查不到即 3300，不泄露存在性（study 会话同口径）。 */
    Optional<PlanEntity> findByIdAndUserId(UUID id, UUID userId);
}
