package io.annona.modules.usage.repository;

import io.annona.modules.usage.model.TokenUsageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 用量账仓库。写走原生 INSERT（账行一次成型、无 merge 回读需求，save/merge 语义陷阱
 * 直接绕开）；聚合读按 model 分组（成本面板与会话钻取的两种口径）。
 */
public interface TokenUsageRepository extends JpaRepository<TokenUsageEntity, UUID> {

    @Modifying
    @Query(value = "INSERT INTO token_usage (id, user_id, scene, session_id, provider, model,"
        + " purpose, prompt_tokens, completion_tokens, prompt_hash, evaluator_version)"
        + " VALUES (:id, :userId, :scene, :sessionId, :provider, :model, :purpose,"
        + " :promptTokens, :completionTokens, :promptHash, :evaluatorVersion)", nativeQuery = true)
    int insertUsage(@Param("id") UUID id, @Param("userId") UUID userId,
                    @Param("scene") String scene, @Param("sessionId") UUID sessionId,
                    @Param("provider") String provider, @Param("model") String model,
                    @Param("purpose") String purpose,
                    @Param("promptTokens") int promptTokens,
                    @Param("completionTokens") int completionTokens,
                    @Param("promptHash") String promptHash,
                    @Param("evaluatorVersion") String evaluatorVersion);

    /**
     * 会话成本钻取（GET /api/usage/session/{id}）：(model, sumPrompt, sumCompletion, calls)。
     * userId 谓词在聚合条件里——成本数据也属用户隐私，猜得到的 session_id 换不来别人的账。
     */
    @Query("select u.model, sum(u.promptTokens), sum(u.completionTokens), count(u)"
        + " from TokenUsageEntity u where u.sessionId = :sessionId and u.userId = :userId"
        + " group by u.model")
    List<Object[]> aggregateBySession(@Param("sessionId") UUID sessionId,
                                      @Param("userId") UUID userId);

    /** 用户日累计（配额兜底与"查看用量"页；批次 2 面板只展示近 N 天，时间倒序限量）。 */
    List<TokenUsageEntity> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);
}
