/**
 * plan 计划与任务模块（P2-06）：MD 计划、AI 拆任务（PLAN scene 计量）、打卡联动
 * 瀑布累计、完整三面板文档工作室。
 *
 * <p>允许依赖：{@code io.annona.shared.direction.service}（DirectionQueryService 只读）、
 * {@code io.annona.common}（含 UsageContext / StreamingChatProvider 端口）。
 *
 * <p>联动语义与拆分 reconcile 的唯一权威定义：docs/specs/2026-10-03-plan-module-adr.md。
 * 事件消费 {@code shared.domain.CheckinLinkedEvent}（study 打卡首建发布，AFTER_COMMIT）。
 */
package io.annona.modules.plan;
