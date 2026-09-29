package io.annona.modules.questionbank.model;

import java.util.List;

/**
 * 追问（qb_question.follow_ups JSONB 元素）：与主问题同构——各自携带参考答案、关键点与
 * 评分标准（借 🅖 KnowledgeBaseQuestionFollowUpDTO 的形状；annona 侧为不可变 record，
 * 由 Hibernate JSON 映射往返，真库正确性由 docker-it 证伪）。
 */
public record QbFollowUp(String question, String referenceAnswer, List<String> keyPoints,
                         String scoringRubric) {
}
