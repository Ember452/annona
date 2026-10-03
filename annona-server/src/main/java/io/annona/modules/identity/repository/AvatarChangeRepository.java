package io.annona.modules.identity.repository;

import io.annona.modules.identity.entity.AvatarChangeEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvatarChangeRepository extends JpaRepository<AvatarChangeEntity, UUID> {

    /** 历史列表（最近优先）；保留窗口裁剪依赖同一排序。 */
    List<AvatarChangeEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<AvatarChangeEntity> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /** 超出保留窗口的旧历史：其对象先由服务层尽力删除，再删行。 */
    void deleteByUserIdAndIdNotIn(UUID userId, List<UUID> keepIds);
}
