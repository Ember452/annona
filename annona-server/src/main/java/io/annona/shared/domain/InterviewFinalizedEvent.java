package io.annona.shared.domain;

import java.util.UUID;

/**
 * 交卷完成领域事件（interview 在交卷赢者分支发布，evaluation 监听后建报告 + 投递评估任务）。
 *
 * <p>发布时机契约：在 {@code @Transactional} 交卷方法内发布，监听方用
 * {@code @TransactionalEventListener}（默认 AFTER_COMMIT 阶段）消费——保证只有交卷真的
 * 提交才触发评估，回滚不评估；LLM/Redis 一律在提交后，符合"外部 IO 不进事务"。
 *
 * @param sessionId        面试会话 ID（评估幂等键宿主）
 * @param userId           会话归属（记账与鉴权主体）
 * @param directionId      方向（报告与用量归属）
 * @param evaluatorVersion 交卷时锁定的会话评估器版本（批 2 恒 'v1'；本事件告知 evaluation 待评的是哪个会话）
 */
public record InterviewFinalizedEvent(UUID sessionId, UUID userId, UUID directionId,
                                      String evaluatorVersion) {
}
