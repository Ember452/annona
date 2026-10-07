package io.annona.modules.voice.repository;

import io.annona.modules.voice.entity.VoiceSessionEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 语音会话仓库：状态迁移一律条件 UPDATE（影响行数即守门），与 interview 状态机同纪律；
 * 转写追加用 JPQL concat 原位拼接——不读改写整段转写，避免并发定稿互相覆盖。
 */
public interface VoiceSessionRepository extends JpaRepository<VoiceSessionEntity, UUID> {

    /** owner 范围内取会话：查不到即不存在（不泄露他人会话存在性，direction findOwned 同口径）。 */
    Optional<VoiceSessionEntity> findByIdAndUserId(UUID id, UUID userId);

    /** 暂停：仅 ACTIVE 可暂停。 */
    @Modifying
    @Query("update VoiceSessionEntity s set s.status = 'PAUSED', s.updatedAt = :now"
        + " where s.id = :id and s.userId = :userId and s.status = 'ACTIVE'")
    int pauseIfActive(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /** 恢复：仅 PAUSED 可恢复。 */
    @Modifying
    @Query("update VoiceSessionEntity s set s.status = 'ACTIVE', s.updatedAt = :now"
        + " where s.id = :id and s.userId = :userId and s.status = 'PAUSED'")
    int resumeIfPaused(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /** 收口：ACTIVE/PAUSED → FINALIZED 并回填收口时刻；影响 0 行 = 已终态（幂等守门）。 */
    @Modifying
    @Query("update VoiceSessionEntity s set s.status = 'FINALIZED', s.finalizedAt = :now,"
        + " s.updatedAt = :now where s.id = :id and s.userId = :userId"
        + " and s.status in ('ACTIVE', 'PAUSED')")
    int finalizeIfOpen(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /** 客户端断连未收口 → ABANDONED（幂等：已终态影响 0 行）。 */
    @Modifying
    @Query("update VoiceSessionEntity s set s.status = 'ABANDONED', s.updatedAt = :now"
        + " where s.id = :id and s.userId = :userId and s.status in ('ACTIVE', 'PAUSED')")
    int abandonIfOpen(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /** 追加一句 VAD 定稿转写（顺序拼接；空白文本由调用方过滤）。 */
    @Modifying
    @Query("update VoiceSessionEntity s set s.transcript = concat(s.transcript, :text),"
        + " s.updatedAt = :now where s.id = :id and s.userId = :userId"
        + " and s.status in ('ACTIVE', 'PAUSED')")
    int appendTranscript(@Param("id") UUID id, @Param("userId") UUID userId,
                         @Param("text") String text, @Param("now") Instant now);
}
