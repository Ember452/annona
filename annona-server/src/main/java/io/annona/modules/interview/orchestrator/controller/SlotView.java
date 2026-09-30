package io.annona.modules.interview.orchestrator.controller;

/**
 * 单个作答槽位视图。
 *
 * @param order         主问题位次（0 起）
 * @param questionId    题目 ID
 * @param followUpIndex 0=主问题，>=1 为第 n 层追问
 * @param questionText  题干/追问题干（断线重进时展示）
 * @param answered      该槽是否已 SUBMITTED
 * @param answerText    已答内容回显（未答为 null）
 */
public record SlotView(int order, String questionId, int followUpIndex, String questionText,
                       boolean answered, String answerText) {
}
