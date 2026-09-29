package io.annona.modules.llmprovider.repository;

import io.annona.modules.llmprovider.entity.LlmProviderConfigEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provider 配置仓库（CRUD 全走 JPA；uq 竞态兜底交给唯一索引，service 层预检只为文案）。 */
public interface LlmProviderConfigRepository
    extends JpaRepository<LlmProviderConfigEntity, UUID> {

    List<LlmProviderConfigEntity> findByUserIdOrderByProviderKeyAscPurposeAsc(UUID userId);

    Optional<LlmProviderConfigEntity> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndProviderKeyAndPurpose(UUID userId, String providerKey,
                                                   String purpose);
}
