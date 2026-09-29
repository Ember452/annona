package io.annona.shared.question;

import java.util.UUID;

/**
 * 组卷候选（题库对消费方的只读视图，shared 读模型）。刻意不含参考答案/评分标准——组卷只需
 * 题干与结构信息，评估内容批 3 按 id 回查（最小知识面，防把评分口径泄露进决策层）。
 *
 * @param id            题目 ID
 * @param question      主问题题干
 * @param difficulty    难度 1–5
 * @param followUpCount 题内追问条数（组卷展平 AnswerSlot 时的上限依据）
 */
public record QuestionCandidate(UUID id, String question, int difficulty, int followUpCount) {
}
