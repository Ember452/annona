package io.annona.modules.qa.repository;

import io.annona.modules.qa.dto.QaCitation;
import io.annona.modules.qa.entity.QaMessageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 条件回填（短事务②）：只更新仍是占位（completed=false）的行。占位已消失（会话被
     * 并发级联删除）或已被回填过（重复终态回调）时影响 0 行，调用方记日志后跳过。
     * **刻意不走 findById+save**：merge 发出的 UPDATE 打到并发删除后的行会抛
     * StaleObjectStateException（CI docker-it 实测）——写竞争用条件语句表达而非乐观锁重试，
     * 与 knowledge 状态机的条件 UPDATE 同一取舍。
     */
    @Modifying
    @Query("update QaMessageEntity m set m.content = :content, m.citations = :citations, "
        + "m.completed = :completed where m.id = :id and m.completed = false")
    int backfill(@Param("id") UUID id, @Param("content") String content,
        @Param("citations") List<QaCitation> citations, @Param("completed") boolean completed);
}
