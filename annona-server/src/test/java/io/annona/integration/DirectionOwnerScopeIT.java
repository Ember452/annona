package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * owner 命名空间由 V1 的 PG15+ 方言（{@code UNIQUE NULLS NOT DISTINCT}）撑起来，
 * 本机无 PG 测不了，只能由 CI docker-it 在真库上坐实：
 * 两个用户可各建同名 key 的方向；同 owner 内重复 key 被约束拒绝。
 * direction.user_id 有 FK，app_user 行用 JdbcTemplate 直插。
 * 另：坐实 created_at 的 insertable=false + DB DEFAULT 链路（flush 后 refresh 回读，
 * DirectionCommandService.create 的 POST 响应同款）；@Transactional 仅为 refresh 可用，
 * 结束即回滚不残留数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Transactional
@Tag("docker")
@DisplayName("direction owner 命名空间（UNIQUE NULLS NOT DISTINCT）在真实 PG 上生效")
class DirectionOwnerScopeIT {

    @Autowired
    private DirectionRepository directionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("不同用户可各建同名 key；同 owner 重复 key 被唯一约束拒绝；createdAt 由 DB default 回读")
    void ownerScopedKeyUniqueness() {
        UUID ownerA = insertUser();
        UUID ownerB = insertUser();

        DirectionEntity first = direction(ownerA, "shared-key", "用户A的方向");
        directionRepository.saveAndFlush(first);
        // created_at 是 insertable=false + DB DEFAULT now()：flush 后 refresh 回读，
        // 保证 POST 响应与 GET 同形（createdAt 非 null）——真 PG 上坐实契约
        entityManager.refresh(first);
        assertThat(first.getCreatedAt()).isNotNull();

        directionRepository.saveAndFlush(direction(ownerB, "shared-key", "用户B的方向"));

        assertThatThrownBy(() -> directionRepository.saveAndFlush(
                direction(ownerA, "shared-key", "用户A的第二个方向")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "insert into app_user (id, email, password_hash, password_algo, status, role)"
                + " values (?, ?, 'x', 'scrypt', 'ACTIVE', 'USER')",
            id, "it-" + id + "@direction.test");
        return id;
    }

    private DirectionEntity direction(UUID owner, String key, String name) {
        DirectionEntity entity = new DirectionEntity();
        entity.setId(UUID.randomUUID());
        entity.setKey(key);
        entity.setName(name);
        entity.setOrigin(DirectionEntity.ORIGIN_USER_CUSTOM);
        entity.setStatus(DirectionEntity.STATUS_ACTIVE);
        entity.setUserId(owner);
        return entity;
    }
}
