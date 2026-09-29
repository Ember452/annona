package io.annona.modules.questionbank.repository;

import io.annona.modules.questionbank.entity.QbQuestionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QbQuestionRepository extends JpaRepository<QbQuestionEntity, UUID> {

    /**
     * 重新出题的替换语义（skill-questionbank-adr §决策 5）：只删 DRAFT、保留 ACTIVE——
     * 用户策展成果（已启用题目）不受重新生成影响。与"写入新草稿"在同一最小事务内执行。
     */
    @Modifying
    @Query("delete from QbQuestionEntity q where q.userId = :userId"
        + " and q.directionId = :directionId and q.status = 'DRAFT'")
    int deleteDrafts(@Param("userId") UUID userId, @Param("directionId") UUID directionId);

    /** 最近已有题目题干（喂给出题提示词防重复；全状态——草稿也算已出过的题）。 */
    @Query("select q.question from QbQuestionEntity q where q.userId = :userId"
        + " and q.directionId = :directionId order by q.createdAt desc, q.id desc")
    List<String> findRecentQuestions(@Param("userId") UUID userId,
                                     @Param("directionId") UUID directionId, Pageable pageable);
}
