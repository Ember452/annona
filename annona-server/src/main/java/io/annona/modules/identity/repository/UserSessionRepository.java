package io.annona.modules.identity.repository;

import io.annona.modules.identity.entity.UserSessionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSessionEntity, UUID> {
}
