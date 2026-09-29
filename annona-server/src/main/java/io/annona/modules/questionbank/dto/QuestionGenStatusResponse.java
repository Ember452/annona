package io.annona.modules.questionbank.dto;

import java.time.Instant;

/**
 * 出题任务状态（generate 的返回与 generation-status 轮询共用形状；字段口径与 SSE 进度
 * 信封互补——信封管实时推送，本形状管轮询兜底与目标参数读回）。
 */
public record QuestionGenStatusResponse(String taskId, String status, int difficulty,
                                        int questionCount, int followUpCount, int savedCount,
                                        int skippedCount, String message, String error,
                                        Instant updatedAt) {
}
