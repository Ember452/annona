package io.annona.modules.plan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * plan_task 计划任务（V17 §2）：打卡联动的承接单位。progress_minutes 只由联动监听器
 * 与拆分 reconcile 维护——任何"手动改进度"入口都违反 ADR §后果。
 */
@Entity
@Table(name = "plan_task")
public class PlanTaskEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 与 V17 列约束同源的取值域（chk_plan_task_category/priority）。 */
    public static final java.util.Set<String> CATEGORIES = java.util.Set.of("study", "project", "review", "exercise");
    public static final java.util.Set<String> PRIORITIES = java.util.Set.of("high", "normal", "low");

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** 冗余 owner（今日待办跨计划直查），由服务层保证与 plan.user_id 一致。 */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 联动匹配方向；null = 不参与打卡自动累计。 */
    @Column(name = "direction_id")
    private UUID directionId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "status", nullable = false)
    private String status;

    /** 目标专注分钟（5..600）；瀑布累计到达即自动 DONE。 */
    @Column(name = "target_minutes", nullable = false)
    private int targetMinutes;

    @Column(name = "progress_minutes", nullable = false)
    private int progressMinutes;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public void setPlanId(UUID planId) {
        this.planId = planId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getDirectionId() {
        return directionId;
    }

    public void setDirectionId(UUID directionId) {
        this.directionId = directionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getTargetMinutes() {
        return targetMinutes;
    }

    public void setTargetMinutes(int targetMinutes) {
        this.targetMinutes = targetMinutes;
    }

    public int getProgressMinutes() {
        return progressMinutes;
    }

    public void setProgressMinutes(int progressMinutes) {
        this.progressMinutes = progressMinutes;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
