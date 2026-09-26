package io.annona.shared.direction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * direction 方向主数据（V1 基线 §8；direction-master-data-adr 修订版：代理键 + owner 命名空间）。
 *
 * <p>{@code parent_id} 与 {@code meta_json} 两列<b>刻意不映射</b>：尚无任何消费方
 * （层级聚合展示是 P2 关注点、meta 是 JSONB 扩展位），未映射列被 Hibernate validate
 * 忽略、由 DB 默认值（NULL / '{}'）兜底；出现需求时再补映射，不预占字段。
 *
 * <p>id 由服务层 {@code UUID.randomUUID()} 赋值，不用 {@code @GeneratedValue}，
 * 与 identity 的 {@code AppUserEntity} 保持同一处理方式。
 */
@Entity
@Table(name = "direction")
public class DirectionEntity {

    public static final String ORIGIN_SKILL_BUILTIN = "SKILL_BUILTIN";
    public static final String ORIGIN_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    public static final String ORIGIN_USER_CUSTOM = "USER_CUSTOM";
    public static final String ORIGIN_JD_PARSED = "JD_PARSED";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** 全小写-dashed（如 java-concurrency）；owner 内唯一（uq_direction_owner_key），非全局唯一。 */
    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "name", nullable = false)
    private String name;

    /** SKILL_BUILTIN | KNOWLEDGE_BASE | USER_CUSTOM | JD_PARSED（chk_direction_origin）。 */
    @Column(name = "origin", nullable = false)
    private String origin;

    /** ACTIVE | ARCHIVED（chk_direction_status）；有历史数据的方向只能归档不能物理删。 */
    @Column(name = "status", nullable = false)
    private String status;

    /** origin=KNOWLEDGE_BASE 时指向 kb_doc.id；kb_doc 表 P1a-05 建，届时补 FK（V1 表注释已预留）。 */
    @Column(name = "kb_doc_id")
    private UUID kbDocId;

    /** USER_CUSTOM 归属用户；内置方向为 NULL。ON DELETE CASCADE 由 DDL 承担。 */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getKbDocId() {
        return kbDocId;
    }

    public void setKbDocId(UUID kbDocId) {
        this.kbDocId = kbDocId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
