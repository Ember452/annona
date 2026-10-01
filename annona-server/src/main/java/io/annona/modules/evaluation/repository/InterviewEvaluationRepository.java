package io.annona.modules.evaluation.repository;

import io.annona.modules.evaluation.entity.InterviewEvaluationEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 逐题评估明细仓库。写走原生 upsert（{@code INSERT..ON CONFLICT DO UPDATE}）：消费者重投
 * 命中同幂等键时更新既有行而非双写（evaluation-pipeline-adr §决策 2，绕开预置主键 save/merge 陷阱）。
 */
public interface InterviewEvaluationRepository
    extends JpaRepository<InterviewEvaluationEntity, UUID> {

    /** 报告装配读：一会话一版本的逐题明细，按题目、追问序。 */
    List<InterviewEvaluationEntity> findBySessionIdAndEvaluatorVersionOrderByQuestionIdAscFollowUpIndexAsc(
        UUID sessionId, String evaluatorVersion);

    /**
     * P1c-05 复习题选择器数据源：该用户该方向历史得分最低的题（仅当前 ACTIVE、非降级评分），
     * 按逐题最低分升序。跨表 JOIN interview_session（定方向/归属）与 qb_question（校 ACTIVE）
     * ——原生 SQL，不 import 其他模块实体。score IS NULL 的降级行不参与（宁缺勿假分）。
     */
    @Query(value = "select q.id from interview_evaluation e"
        + " join interview_session s on s.id = e.session_id"
        + " join qb_question q on q.id = e.question_id"
        + " where s.user_id = :userId and s.direction_id = :directionId"
        + " and q.user_id = :userId and q.status = 'ACTIVE' and e.score is not null"
        + " group by q.id order by min(e.score) asc limit :limit", nativeQuery = true)
    List<UUID> findWeakestQuestionIds(@Param("userId") UUID userId,
                                      @Param("directionId") UUID directionId,
                                      @Param("limit") int limit);

    /**
     * 幂等 upsert：{@code strengthsJson}/{@code improvementsJson} 为调用方序列化的 JSON 字符串
     * （原生查询按 CAST AS jsonb 落列）。同幂等键冲突时用新值覆盖明细并推进 updated_at。
     */
    @Modifying
    @Query(value = "INSERT INTO interview_evaluation (id, session_id, question_id, follow_up_index,"
        + " evaluator_version, score, feedback, strengths, improvements, fallback_used,"
        + " raw_response, updated_at)"
        + " VALUES (:id, :sessionId, :questionId, :followUpIndex, :evaluatorVersion, :score,"
        + " :feedback, CAST(:strengthsJson AS jsonb), CAST(:improvementsJson AS jsonb),"
        + " :fallbackUsed, :rawResponse, :now)"
        + " ON CONFLICT (session_id, question_id, follow_up_index, evaluator_version)"
        + " DO UPDATE SET score = EXCLUDED.score, feedback = EXCLUDED.feedback,"
        + " strengths = EXCLUDED.strengths, improvements = EXCLUDED.improvements,"
        + " fallback_used = EXCLUDED.fallback_used, raw_response = EXCLUDED.raw_response,"
        + " updated_at = EXCLUDED.updated_at",
        nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("sessionId") UUID sessionId,
               @Param("questionId") UUID questionId, @Param("followUpIndex") short followUpIndex,
               @Param("evaluatorVersion") String evaluatorVersion, @Param("score") Short score,
               @Param("feedback") String feedback, @Param("strengthsJson") String strengthsJson,
               @Param("improvementsJson") String improvementsJson,
               @Param("fallbackUsed") boolean fallbackUsed,
               @Param("rawResponse") String rawResponse, @Param("now") Instant now);
}
