package io.annona.modules.study.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * study_session 一次专注/学习会话（V2 §2；study-collection-adr）。
 *
 * <p>{@code end_at IS NULL} 即进行中，不设 status 列（ADR §后果）。minutes/quality
 * 只在 finish 时由服务端写入——前端上报一律不采信（ADR §决策 2）。
 *
 * <p>id 由服务层 {@code UUID.randomUUID()} 赋值，与 direction/identity 同一处理方式。
 */
@Entity
@Table(name = "study_session")
public class StudySessionEntity {

    public static final String MODE_POMODORO = "POMODORO";
    public static final String MODE_IMMERSIVE = "IMMERSIVE";
    public static final String MODE_CHECKIN = "CHECKIN";

    public static final String QUALITY_VERIFIED = "VERIFIED";
    public static final String QUALITY_PARTIAL = "PARTIAL";
    public static final String QUALITY_SELF_REPORTED = "SELF_REPORTED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 业务表的方向列一律外键到 direction.id（direction ADR），此处仅存 id 值。 */
    @Column(name = "direction_id", nullable = false)
    private UUID directionId;

    /** 打卡 hours 联动的会话（ADR §决策 3）；更新打卡按本列定位。 */
    @Column(name = "checkin_id")
    private UUID checkinId;

    /** POMODORO | IMMERSIVE | CHECKIN（chk_study_session_mode）。 */
    @Column(name = "mode", nullable = false)
    private String mode;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    /** NULL = 进行中。 */
    @Column(name = "end_at")
    private Instant endAt;

    /** finish 时服务端按心跳时间线计算；进行中为 NULL。 */
    @Column(name = "minutes")
    private Integer minutes;

    /** VERIFIED | PARTIAL | SELF_REPORTED（chk_study_session_quality）。 */
    @Column(name = "quality")
    private String quality;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

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

    public UUID getCheckinId() {
        return checkinId;
    }

    public void setCheckinId(UUID checkinId) {
        this.checkinId = checkinId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public Integer getMinutes() {
        return minutes;
    }

    public void setMinutes(Integer minutes) {
        this.minutes = minutes;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
