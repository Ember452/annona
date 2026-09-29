package io.annona.modules.interview.orchestrator.session;

import java.util.UUID;

/**
 * 作答槽位：组卷结果展平后的一个待占位坑（主问题 1 个 + 每题追问 followUpDepth 个）。
 *
 * @param questionId    题目 ID（qb_question.id）
 * @param followUpIndex 0 = 主问题，>=1 为第 n 层追问
 */
public record AnswerSlot(UUID questionId, short followUpIndex) {

    public AnswerSlot(UUID questionId, int followUpIndex) {
        this(questionId, (short) followUpIndex);
    }
}
