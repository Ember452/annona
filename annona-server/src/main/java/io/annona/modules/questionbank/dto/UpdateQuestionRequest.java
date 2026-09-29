package io.annona.modules.questionbank.dto;

import io.annona.modules.questionbank.model.QbFollowUp;
import java.util.List;

/** 题目编辑请求（题干/摘要/参考答案/关键点/评分标准/追问）；null 字段不更新。 */
public record UpdateQuestionRequest(String question, String topicSummary, String referenceAnswer,
                                    List<String> keyPoints, String scoringRubric,
                                    List<QbFollowUp> followUps) {
}
