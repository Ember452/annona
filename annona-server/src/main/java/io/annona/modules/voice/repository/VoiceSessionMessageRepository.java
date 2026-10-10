package io.annona.modules.voice.repository;

import io.annona.modules.voice.entity.VoiceSessionMessageEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceSessionMessageRepository
    extends JpaRepository<VoiceSessionMessageEntity, UUID> {

    /** 会话内全部轮次（seq 保序）——LLM 历史装配与评估作答装配共用。 */
    List<VoiceSessionMessageEntity> findBySessionIdOrderBySeqAsc(UUID sessionId);

    /** 已关联题目的作答轮（评估消费的最小集；未关联题目的轮不进评分）。 */
    List<VoiceSessionMessageEntity> findBySessionIdAndRoleAndQuestionIdIsNotNullOrderBySeqAsc(
        UUID sessionId, String role);

    /** 最新一轮（seq 分配用；会话内轮次单连接串行推进，无并发竞争面）。 */
    Optional<VoiceSessionMessageEntity> findTopBySessionIdOrderBySeqDesc(UUID sessionId);
}
