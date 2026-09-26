package io.annona.modules.study.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * checkin 每日打卡（V2 §1）。一天一条（uq_checkin_user_day）是幂等 upsert 的 DB 依据——
 * 重复提交即更新，而非拒绝（上游在 UI 层挡，annona 下沉到 DB，ADR §决策 4）。
 */
@Entity
@Table(name = "checkin")
public class CheckinEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 打卡必选方向（§5.1：科目 = 方向下拉 + 可即时新建）。 */
    @Column(name = "direction_id", nullable = false)
    private UUID directionId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    /** NUMERIC(4,1)，0..24；>0 时同事务联动落 SELF_REPORTED 会话（ADR §决策 3）。 */
    @Column(name = "hours", nullable = false)
    private BigDecimal hours;

    @Column(name = "mood")
    private String mood;

    /** 1..5 自评能量值（chk_checkin_energy），可空。 */
    @Column(name = "energy")
    private Integer energy;

    @Column(name = "note")
    private String note;

    /** 打卡截图（RustFS/S3 object key）；上传链路属后续阶段，先留列。 */
    @Column(name = "snapshot_url")
    private String snapshotUrl;

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

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public BigDecimal getHours() {
        return hours;
    }

    public void setHours(BigDecimal hours) {
        this.hours = hours;
    }

    public String getMood() {
        return mood;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public Integer getEnergy() {
        return energy;
    }

    public void setEnergy(Integer energy) {
        this.energy = energy;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getSnapshotUrl() {
        return snapshotUrl;
    }

    public void setSnapshotUrl(String snapshotUrl) {
        this.snapshotUrl = snapshotUrl;
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
