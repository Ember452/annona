package io.annona.modules.qa.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 历史消息项（{@code GET /api/qa/sessions/{sessionId}/messages}）。
 *
 * @param id           消息 id
 * @param type         USER / ASSISTANT
 * @param content      正文；ASSISTANT 中断保留时是已生成的部分内容
 * @param completed    false = 流未正常结束（前端可渲染"回答中断"提示）
 * @param citations    结构化引用；仅 ASSISTANT 完整回答携带
 * @param messageOrder 会话内序号
 * @param createdAt    落库时间
 * @param missReason   检索空命中诊断（NO_READY_DOC/MODEL_MISMATCH/NO_MATCH；MATCHED 与
 *                     USER 行为 null）——历史视图的"凭什么没找到"（V7）
 */
public record QaMessageResponse(UUID id, String type, String content, boolean completed,
                                List<QaCitation> citations, int messageOrder, Instant createdAt,
                                String missReason) {
}
