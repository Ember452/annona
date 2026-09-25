package io.annona.spi.dto;

/**
 * 一道被 planner 选中的候选题。planner 的输出与 interview/orchestrator 的输入契约。
 *
 * @param questionId   题目主键
 * @param directionKey 所属方向（{@code DirectionKey} 值对象序列化后的字符串；见
 *                     docs/specs/2026-09-25-direction-master-data-adr.md）
 * @param difficulty   难度（1–5）
 * @param reason       选题理由，供可解释面板展示；禁止为 {@code null}
 */
public record PlannedQuestion(String questionId, String directionKey, int difficulty, String reason) {
}
