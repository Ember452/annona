package io.annona.spi.dto;

/**
 * 一道被 planner 选中的候选题。planner 的输出与 interview/orchestrator 的输入契约。
 *
 * @param questionId  题目主键
 * @param directionId 所属方向（direction.id 的字符串形态）——direction ADR 修订 2 的
 *                    遗留义务已在此定稿（2026-09-30，P1c planner 消费前落地；业务表/契约
 *                    一律 direction.id 外键，禁存 key 字符串）
 * @param difficulty  难度（1–5）
 * @param reason      选题理由，供可解释面板展示；禁止为 {@code null}
 */
public record PlannedQuestion(String questionId, String directionId, int difficulty, String reason) {
}
