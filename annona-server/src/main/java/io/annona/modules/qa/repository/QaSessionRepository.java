package io.annona.modules.qa.repository;

import io.annona.modules.qa.entity.QaSessionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * qa_session 仓储。列表按最近活跃倒序（updated_at 由应用侧推进，V6 表注释）。
 */
public interface QaSessionRepository extends JpaRepository<QaSessionEntity, UUID> {

    List<QaSessionEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
