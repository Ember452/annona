package io.annona.modules.identity.repository;

import io.annona.modules.identity.entity.LoginAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAttemptRepository extends JpaRepository<LoginAttemptEntity, String> {
}
