package io.annona.modules.study.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * study_event 会话内原子事件（V2 §3）。
 *
 * <p>{@code payload} JSONB 列<b>刻意不映射</b>（direction 先例）：尚无任何消费方，
 * 未映射列被 Hibernate validate 忽略、由 DB 默认值（'{}'）兜底；出现需求时再补映射。
 * HEARTBEAT 刻意不在事件枚举内——心跳是活着的证明，只服务质量判定（ADR §背景）。
 */
@Entity
@Table(name = "study_event")
public class StudyEventEntity {

    public static final String TYPE_START = "START";
    public static final String TYPE_BLUR = "BLUR";
    public static final String TYPE_FINISH = "FINISH";
    public static final String TYPE_INTERRUPT = "INTERRUPT";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** START | BLUR | FINISH | INTERRUPT（chk_study_event_type）。 */
    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "at", nullable = false)
    private Instant at;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }
}
