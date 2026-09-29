package io.annona.modules.questionbank.dto;

import io.annona.modules.questionbank.model.QbFollowUp;
import java.time.Instant;
import java.util.List;

/** 题目条目（题库列表/详情共用；followUps 与主问题同构，rubric 前端可展开）。 */
public record QuestionResponse(String id, String question, String topicSummary,
                               String referenceAnswer, List<String> keyPoints,
                               String scoringRubric, int difficulty, List<QbFollowUp> followUps,
                               String status, Instant createdAt) {
}
