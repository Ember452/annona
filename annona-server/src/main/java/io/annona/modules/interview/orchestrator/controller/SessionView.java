package io.annona.modules.interview.orchestrator.controller;

import java.util.List;

/**
 * 会话视图（面试中心唯一对外形状；禁出 Entity）。slots 按组卷执行定稿展平：
 * 每题 1 个主槽（followUpIndex=0）+ 实际追问槽，order 为主问题位次。
 *
 * @param id            会话 ID
 * @param directionId   方向 ID
 * @param status        RESUMABLE | COMPLETED | ABANDONED
 * @param currentIndex  恢复位（主问题位次）
 * @param totalCount    主问题总数
 * @param answeredCount 已 SUBMITTED 槽数（含主题与追问）
 * @param slots         展平槽位
 * @param skippedReasons 组卷缺口说明（本次开面的诚实告知，可为空）
 * @param startedAt     开始时间（ISO）
 */
public record SessionView(String id, String directionId, String status, int currentIndex,
                          int totalCount, int answeredCount, List<SlotView> slots,
                          List<String> skippedReasons, String startedAt) {

    public SessionView {
        slots = List.copyOf(slots);
        skippedReasons = List.copyOf(skippedReasons);
    }
}
