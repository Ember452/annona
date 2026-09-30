package io.annona.shared.question;

import java.util.List;
import java.util.UUID;

/**
 * 题目评分明细（评估链专用读视图，P1b-06）。与 {@link QuestionStemDetail}/{@link QuestionCandidate}
 * 相反，<b>本视图刻意携带评分口径</b>（参考答案/关键点/rubric/难度）——只有 evaluation 消费，
 * 组卷与会话展示走另两个最小视图，评分内容不外泄进决策层（interview-session-adr §最小面：
 * "评估内容批 3 按 id 回查"）。
 *
 * @param id               题目 ID
 * @param question         主问题题干
 * @param referenceAnswer  主问题参考答案
 * @param keyPoints        主问题关键点（评分命中锚点）
 * @param scoringRubric    主问题评分标准
 * @param difficulty       难度 1..5（加权总分输入）
 * @param followUps        追问评分明细（保序，索引即 follow_up_index）
 */
public record QuestionGrading(UUID id, String question, String referenceAnswer,
                              List<String> keyPoints, String scoringRubric, int difficulty,
                              List<FollowUpGrading> followUps) {

    /**
     * 追问评分明细（与主问题同构的评分子集）。
     *
     * @param question        追问题干
     * @param referenceAnswer 参考答案
     * @param keyPoints       关键点
     * @param scoringRubric   评分标准
     */
    public record FollowUpGrading(String question, String referenceAnswer,
                                  List<String> keyPoints, String scoringRubric) {
    }

    public QuestionGrading {
        keyPoints = List.copyOf(keyPoints);
        followUps = List.copyOf(followUps);
    }
}
