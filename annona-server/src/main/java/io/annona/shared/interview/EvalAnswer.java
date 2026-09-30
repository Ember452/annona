package io.annona.shared.interview;

import java.util.UUID;

/**
 * 单槽作答（评估消费的最小视图）：定位键 + 作答文本，不含评分口径（评分子经
 * {@code shared.question.gradingByIds} 按 questionId 回查，两视图分开）。
 *
 * @param questionId     题目 ID
 * @param followUpIndex  0=主问题，>=1 为第 n 层追问
 * @param answerText     作答正文；null = 该槽弃答（交卷时未作答也置 SUBMITTED，文本为空）
 */
public record EvalAnswer(UUID questionId, int followUpIndex, String answerText) {
}
