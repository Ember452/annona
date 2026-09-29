package io.annona.modules.interview.orchestrator.plan;

import java.util.List;

/**
 * 组卷计划（interview-session-adr §决策 2）：客户端发起会话的期望形态，服务端校验通过后
 * 以 JSONB 快照落 {@code interview_session.plan}——落库的是<b>校验后的定稿</b>，不是客户端原文。
 *
 * <p>校验规则钉在紧凑构造器里（越界即 {@code IllegalArgumentException}，由 Controller 层
 * 翻成 {@code 1001}）：构造出的实例天然合法，不存在"先建后验"的中间态。上限 totalCount=20 /
 * followUpDepth=3 的取值依据：单场面试注意力窗口（20 题 ≈ 60 分钟）与追问深度实践口径
 * （超过 3 层追问的题干质量在 🅖 评测中显著衰减）；结构版本由校验器语义持有，plan JSON 内
 * 不设 {@code v} 字段（运行期无人按版本分支，加了就是死字段——ADR 否决表）。
 *
 * @param totalCount    主问题数，[1, 20]
 * @param difficulties  逐槽难度（1–5），长度必须等于 totalCount——难度序列而非单值，
 *                      因为批 3 的掌握度反哺需要"同一场里跨难度"的观测点
 * @param followUpDepth 每题追问层数，[0, 3]
 */
public record InterviewPlan(int totalCount, List<Integer> difficulties, int followUpDepth) {

    public static final int MIN_TOTAL = 1;
    public static final int MAX_TOTAL = 20;
    public static final int MIN_DIFFICULTY = 1;
    public static final int MAX_DIFFICULTY = 5;
    public static final int MAX_FOLLOW_UP_DEPTH = 3;

    public InterviewPlan {
        difficulties = List.copyOf(difficulties);
        if (totalCount < MIN_TOTAL || totalCount > MAX_TOTAL) {
            throw new IllegalArgumentException("totalCount 须在 [1,20]，得到 " + totalCount);
        }
        if (difficulties.size() != totalCount) {
            throw new IllegalArgumentException(
                "difficulties 长度须等于 totalCount，得到 " + difficulties.size());
        }
        for (int d : difficulties) {
            if (d < MIN_DIFFICULTY || d > MAX_DIFFICULTY) {
                throw new IllegalArgumentException("难度须在 [1,5]，得到 " + d);
            }
        }
        if (followUpDepth < 0 || followUpDepth > MAX_FOLLOW_UP_DEPTH) {
            throw new IllegalArgumentException("followUpDepth 须在 [0,3]，得到 " + followUpDepth);
        }
    }
}
