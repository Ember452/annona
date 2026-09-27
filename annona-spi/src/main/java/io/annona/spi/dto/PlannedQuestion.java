package io.annona.spi.dto;

/**
 * 一道被 planner 选中的候选题。planner 的输出与 interview/orchestrator 的输入契约。
 *
 * @param questionId   题目主键
 * @param directionKey 所属方向的引用串。命名待定稿：direction ADR 修订 2 已把
 *                     {@code DirectionKey} 值对象作废，首个真实消费方落地前
 *                     （P1b-01/P1c-01，见该 ADR 遗留义务）统一定为 directionId
 * @param difficulty   难度（1–5）
 * @param reason       选题理由，供可解释面板展示；禁止为 {@code null}
 */
public record PlannedQuestion(String questionId, String directionKey, int difficulty, String reason) {
}
