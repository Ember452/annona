package io.annona.shared.question;

import java.util.List;
import java.util.UUID;

/**
 * 题目展示明细（会话视图装配用）：只含题干与追问文本，<b>刻意不含参考答案/关键点/评分标准</b>
 * （interview-session-adr §最小知识面——候选视图与展示视图是两种消费，都不该拿到评分口径）。
 *
 * @param id                题目 ID
 * @param question          主问题题干
 * @param followUpQuestions 追问题干（保序，索引即 follow_up_index）
 */
public record QuestionStemDetail(UUID id, String question, List<String> followUpQuestions) {

    public QuestionStemDetail {
        followUpQuestions = List.copyOf(followUpQuestions);
    }
}
