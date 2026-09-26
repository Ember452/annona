package io.annona.modules.identity.repository;

import io.annona.modules.identity.entity.AppUserEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    /** 只匹配活跃用户（partial unique index 语义：{@code deleted_at IS NULL}）。 */
    @Query("select u from AppUserEntity u where lower(u.email) = lower(:email) and u.deletedAt is null")
    Optional<AppUserEntity> findActiveByEmail(@Param("email") String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);
}
