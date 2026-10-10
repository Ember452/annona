package io.annona.shared.domain;

import java.util.UUID;

/**
 * 语音会话收口领域事件（voice 在收口赢者分支发布，evaluation 监听后建 VOICE 报告 +
 * 投递评估任务；voice-adr 修订 1）。发布时机契约与 {@link InterviewFinalizedEvent} 一致：
 * 事务内发布，{@code @TransactionalEventListener}(AFTER_COMMIT) 消费。
 *
 * <p>独立事件而非复用 InterviewFinalizedEvent：sessionId 宿主是 voice_session，
 * 评估侧据此建 sessionType=VOICE 的报告并经 VoiceEvalQueryService 装配作答。
 *
 * @param sessionId        语音会话 ID（评估幂等键宿主）
 * @param userId           会话归属
 * @param directionId      方向（报告与用量归属；未绑定方向为 null）
 */
public record VoiceSessionFinalizedEvent(UUID sessionId, UUID userId, UUID directionId) {
}
