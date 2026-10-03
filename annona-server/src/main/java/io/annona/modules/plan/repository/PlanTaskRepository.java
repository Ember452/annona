package io.annona.modules.plan.repository;

import io.annona.modules.plan.entity.PlanTaskEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanTaskRepository extends JpaRepository<PlanTaskEntity, UUID> {

    /** 拆分 reconcile 与任务抽屉按 plan 全量取（任务数 ≤30，一次全取可承受）。 */
    List<PlanTaskEntity> findByPlanIdOrderByCreatedAtAsc(UUID planId);

    /** 手动勾选状态时的定位查询（plan + user 双重归属校验）。 */
    Optional<PlanTaskEntity> findByIdAndPlanIdAndUserId(UUID id, UUID planId, UUID userId);

    /** 今日待办：某状态（PENDING）的跨计划任务。 */
    List<PlanTaskEntity> findByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, String status);

    /** 打卡联动瀑布取数：user + direction + PENDING，最旧优先（ADR §决策 3）。 */
    List<PlanTaskEntity> findByUserIdAndDirectionIdAndStatusOrderByCreatedAtAsc(
        UUID userId, UUID directionId, String status);

    /**
     * 列表页进度摘要的批量聚合（plan_id → [总数, 完成数]）：替代逐计划 2N 次 count
     * 查询（AGENTS §0“禁循环调 DB”；P2 审查修正）。
     */
    @Query("SELECT t.planId, COUNT(t), "
        + "SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END) "
        + "FROM PlanTaskEntity t WHERE t.userId = :userId GROUP BY t.planId")
    List<Object[]> countSummaryByUser(@Param("userId") UUID userId);

    void deleteByPlanIdAndStatusAndIdNotIn(UUID planId, String status, List<UUID> keepIds);
}
