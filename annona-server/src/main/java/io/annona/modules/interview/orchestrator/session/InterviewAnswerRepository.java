package io.annona.modules.interview.orchestrator.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 作答仓库。作答与交卷终态都是条件 UPDATE；归属校验借 {@code interview_session} 子查询
 * （answer 表不冗余 user_id——V9 形状，会话行是唯一归属真值）。
 */
public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswerEntity, UUID> {

    List<InterviewAnswerEntity> findBySessionIdOrderByQuestionIdAscFollowUpIndexAsc(UUID sessionId);

    /**
     * 单题作答：槽位存在且仍 PENDING 才写入（0 行 = 槽位不符或已提交，调用方翻 2703）。
     * 条件里钉死 PENDING：重答已提交题返回 0 行而不是静默覆盖——回滚路径与多端并发下，
     * “答过的不会被悄悄改掉”是用户可感知的不变量（失败文案由 T5 给“这题已答过”）。
     */
    @Modifying
    @Query("update InterviewAnswerEntity a set a.answerText = :text,"
        + " a.answerStatus = 'SUBMITTED', a.submittedAt = :now, a.updatedAt = :now"
        + " where a.sessionId = :sessionId and a.questionId = :questionId"
        + " and a.followUpIndex = :followUpIndex and a.answerStatus = 'PENDING'"
        + " and a.sessionId in (select s.id from InterviewSessionEntity s"
        + " where s.userId = :userId)")
    int submitAnswer(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId,
                     @Param("questionId") UUID questionId,
                     @Param("followUpIndex") short followUpIndex,
                     @Param("text") String text, @Param("now") Instant now);

    /** 交卷赢者的批量终态：未作答槽位也落 SUBMITTED（空答案=弃答，批 3 评估按 null 处理）。 */
    @Modifying
    @Query("update InterviewAnswerEntity a set a.answerStatus = 'SUBMITTED',"
        + " a.submittedAt = :now, a.updatedAt = :now"
        + " where a.sessionId = :sessionId and a.answerStatus = 'PENDING'")
    int markAllSubmitted(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    /**
     * 去重向量判（interview-session-adr §决策 4，M3）：候选 ACTIVE 行×该用户窗口内出现过
     * 的题目行库内 cosine，返回 (candidateId, maxCos) 原始相似值（阈值判定在
     * QuestionDedupService，SQL 不定口径）。任一侧 embedding 为 NULL 的行不参与——降级
     * 关键词判的路径就长在这条条件里；真库行为由 docker 组 InterviewSessionFlowIT 钉死。
     */
    @Query(value = "SELECT c.id, MAX(1 - (c.embedding <=> h.embedding)) AS max_cos"
        + " FROM qb_question c"
        + " JOIN qb_question h ON h.id IN ("
        + "   SELECT ia.question_id FROM interview_answer ia"
        + "   JOIN interview_session s ON s.id = ia.session_id"
        + "   WHERE s.user_id = :userId AND s.direction_id = :directionId"
        + "     AND s.started_at >= :since)"
        + " WHERE c.user_id = :userId AND c.direction_id = :directionId"
        + "   AND c.status = 'ACTIVE' AND c.embedding IS NOT NULL AND h.embedding IS NOT NULL"
        + " GROUP BY c.id", nativeQuery = true)
    List<Object[]> maxCosineToAnswered(@Param("userId") UUID userId,
                                       @Param("directionId") UUID directionId,
                                       @Param("since") Instant since);

    /** 窗口内出现过的题干（关键词判的历史集；含 PENDING 占位——建会话即“见过”）。 */
    @Query(value = "SELECT DISTINCT h.question FROM interview_answer ia"
        + " JOIN interview_session s ON s.id = ia.session_id"
        + " JOIN qb_question h ON h.id = ia.question_id"
        + " WHERE s.user_id = :userId AND s.direction_id = :directionId"
        + "   AND s.started_at >= :since", nativeQuery = true)
    List<String> recentAnsweredStems(@Param("userId") UUID userId,
                                     @Param("directionId") UUID directionId,
                                     @Param("since") Instant since);
}
