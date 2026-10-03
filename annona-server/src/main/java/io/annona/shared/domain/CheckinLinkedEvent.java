package io.annona.shared.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 打卡联动事件（P2-06，plan-module-adr §决策 3）：打卡<b>首次创建且 hours&gt;0</b> 时由
 * CheckinService 在事务内发布，plan 侧监听器在 AFTER_COMMIT 瀑布累计。
 *
 * <p>只发一次是幂等策略的全部：当日修改打卡时长不再补发（差额重算被 ADR 否决）。
 *
 * @param userId      打卡人
 * @param directionId 打卡方向（联动匹配键）
 * @param minutes     打卡时长（分钟，hours×60）
 * @param day         打卡日
 * @param checkinId   打卡行 id（留痕排查用，当前消费方不按它去重）
 */
public record CheckinLinkedEvent(UUID userId, UUID directionId, int minutes, LocalDate day,
                                 UUID checkinId) {
}
