package io.annona.modules.study.repository;

import io.annona.modules.study.entity.StudyEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 事件只写不读（审计留痕，P1a 无消费方）；无自定义查询即空接口。 */
public interface StudyEventRepository extends JpaRepository<StudyEventEntity, UUID> {
}
