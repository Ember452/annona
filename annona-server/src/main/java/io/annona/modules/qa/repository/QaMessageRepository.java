package io.annona.modules.qa.repository;

import io.annona.modules.qa.entity.QaMessageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * qa_message 仓储。session_id 外键索引由 uq_qa_message_session_order 最左前缀覆盖。
 */
public interface QaMessageRepository extends JpaRepository<QaMessageEntity, UUID> {

    List<QaMessageEntity> findBySessionIdOrderByMessageOrderAsc(UUID sessionId);

    /** 追问组装上下文只取完整回答，避免把半截回答当上下文（借 🅖 findRecentCompletedBySessionId）。 */
    List<QaMessageEntity> findBySessionIdAndCompletedTrueOrderByMessageOrderAsc(UUID sessionId);

    /** 下一条消息的序号来源（max+1）；同会话并发由唯一约束兜底（ADR §后果与约束）。 */
    @Query("select coalesce(max(m.messageOrder), 0) from QaMessageEntity m where m.sessionId = :sessionId")
    int findMaxOrder(@Param("sessionId") UUID sessionId);
}
