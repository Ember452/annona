package io.annona.modules.interview.orchestrator.controller;

/**
 * 会话摘要（在途列表项，不含题目）。
 *
 * @param id           会话 ID
 * @param directionId  方向 ID
 * @param status       状态
 * @param currentIndex 恢复位
 * @param totalCount   主问题数
 * @param startedAt    开始时间（ISO）
 */
public record SessionSummary(String id, String directionId, String status, int currentIndex,
                             int totalCount, String startedAt) {
}
