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
}
