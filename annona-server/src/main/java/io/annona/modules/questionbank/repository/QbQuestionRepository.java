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

    /** 题库列表（可空过滤：状态 / 难度 / 题干关键词），时间倒序。 */
    @Query("select q from QbQuestionEntity q where q.userId = :userId"
        + " and q.directionId = :directionId"
        + " and (:status is null or q.status = :status)"
        + " and (:difficulty is null or q.difficulty = :difficulty)"
        + " and (:keyword is null or lower(q.question) like lower(concat('%', :keyword, '%')))"
        + " order by q.createdAt desc, q.id desc")
    List<QbQuestionEntity> search(@Param("userId") UUID userId,
                                  @Param("directionId") UUID directionId,
                                  @Param("status") String status,
                                  @Param("difficulty") Short difficulty,
                                  @Param("keyword") String keyword);

    /**
     * 题干向量回填（V9 M3，best-effort）：实体刻意不映射 embedding 列（kb_doc_chunk 先例），
     * 写入走字面量 CAST。逐行而非批量 CASE：一次出题 N≤20、向量字面量拼 CASE 既难读又有
     * 注入面，N 大了（池生成）再改 COPY 子命令——现在不值得。
     */
    @Modifying
    @Query(value = "UPDATE qb_question SET embedding = CAST(:vec AS vector) WHERE id = :id",
        nativeQuery = true)
    int updateEmbedding(@Param("id") UUID id, @Param("vec") String vectorLiteral);
}
