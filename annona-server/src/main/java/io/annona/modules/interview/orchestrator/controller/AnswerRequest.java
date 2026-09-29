package io.annona.modules.interview.orchestrator.controller;

/**
 * POST /sessions/{id}/answers 请求体。
 *
 * @param questionId    作答题目 ID
 * @param followUpIndex 0=主问题，>=1 为追问层
 * @param answerText    作答文本（空串=弃答该槽）
 */
public record AnswerRequest(String questionId, int followUpIndex, String answerText) {
}
