package io.annona.modules.identity.repository;

import io.annona.modules.identity.entity.UserProfileEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {
}
