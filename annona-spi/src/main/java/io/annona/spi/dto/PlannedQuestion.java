package io.annona.spi.dto;

/**
 * 一道被 planner 选中的候选题。planner 的输出与 interview/orchestrator 的输入契约。
 *
 * @param questionId  题目主键
 * @param directionId 所属方向的 direction.id 外键。命名定稿（planner-decision-kernel-adr，
 *                    收 direction ADR 修订 2 遗留）：统一用 directionId，存字符串 key 无法
 *                    定位归属（key 只在 owner 内唯一），全仓禁止用自由文本表示方向
 * @param difficulty  难度（1–5）
 * @param reason      选题理由，供可解释面板展示；禁止为 {@code null}
 */
public record PlannedQuestion(String questionId, String directionId, int difficulty, String reason) {
}
