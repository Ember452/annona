package io.annona.modules.evaluation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 可比性与难度加权的规则常量（P1b-07，决策算法 ADR 项：每个取值口径与依据写这里，
 * 改值必须同步 evaluation-pipeline-adr 与 golden 快照）。纯函数、无状态，便于 golden 钉值。
 *
 * <p>总分与趋势是两件事：{@link #weightedTotal} 把逐题分按难度折算成一个 0..100 的总分；
 * {@link Trace}/{@link #breakReason} 判定"两次评估是否可比"——报告趋势图据此在不可比处断开
 * （换评分模型/提示版本 = 口径变了，连线的斜率不再有意义）。
 */
public final class ComparabilityRules {

    /**
     * 难度权重线性映射 {@code weight = WEIGHT_BASE + WEIGHT_STEP * (difficulty - 1)}。
     * 取 1.0 起、每档 +0.125：难度 5 的权重是难度 1 的 1.5 倍——够让"答对难题"明显拉开分差，
     * 又不至于让一道 5 难度题压倒整场（上限 1.5 而非 2.0 的取舍：面试样本量小，防单题主导总分）。
     * 校准触发：掌握度/难度标定有实测分布后回写（P1c 决策层）。
     */
    public static final double WEIGHT_BASE = 1.0;
    public static final double WEIGHT_STEP = 0.125;

    /** 难度域（与 chk_question_difficulty 一致）。 */
    public static final int MIN_DIFFICULTY = 1;
    public static final int MAX_DIFFICULTY = 5;

    /**
     * 题目已删/难度读不回时的缺省难度：取域中值 3（权重 1.25，不往任一侧偏）。
     * 口径见 evaluation-pipeline-adr §决策 6；触发重评：命中此分支的题占比有实测数据后
     * 若非 negligible，改为建库时冗余快照难度而不是读时缺省。
     */
    public static final int DEFAULT_DIFFICULTY = 3;

    private ComparabilityRules() {
    }

    /** 单档难度权重（越界钳到 [1,5]）。 */
    public static double weight(int difficulty) {
        int d = Math.max(MIN_DIFFICULTY, Math.min(MAX_DIFFICULTY, difficulty));
        return WEIGHT_BASE + WEIGHT_STEP * (d - 1);
    }

    /**
     * 难度加权总分：{@code Σ score_i * weight_i / Σ weight_i}，四舍五入到 0..100。
     * 降级/无分题（{@link ScoredAnswer#gradable()} 为 false）不进分子分母（宁缺勿假）。
     * 全部不可计算时返回 0（一场全弃答 = 0 分，与"暂无分数"由报告 status 区分）。
     */
    public static int weightedTotal(List<ScoredAnswer> answers) {
        double weighted = 0;
        double weightSum = 0;
        for (ScoredAnswer a : answers) {
            if (!a.gradable()) {
                continue;
            }
            double w = weight(a.difficulty());
            weighted += a.score() * w;
            weightSum += w;
        }
        return weightSum == 0 ? 0 : (int) Math.round(weighted / weightSum);
    }

    /** 加权总分的输入单元：一个可评分槽位的得分与其题目难度。 */
    public record ScoredAnswer(int score, int difficulty, boolean gradable) {

        /** 降级/无分槽位的构造（不计入总分）。 */
        public static ScoredAnswer notGradable(int difficulty) {
            return new ScoredAnswer(0, difficulty, false);
        }
    }

    /**
     * 可比性留痕（一次评估运行的身份）。两次评估可比 ⟺ 四元组全等；任一变化即口径改变，
     * 趋势图必须在此断开（interview_report 落这四列即为此刻判定）。
     */
    public record Trace(String evaluatorVersion, String evaluatorModel, String chatModel,
                        String promptHash) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Trace t && evaluatorVersion.equals(t.evaluatorVersion)
                && java.util.Objects.equals(evaluatorModel, t.evaluatorModel)
                && java.util.Objects.equals(chatModel, t.chatModel)
                && java.util.Objects.equals(promptHash, t.promptHash);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(evaluatorVersion, evaluatorModel, chatModel, promptHash);
        }
    }

    /**
     * 相邻两次评估是否可比；不可比时返回断开的<b>原因</b>（人话，指名变化的维度），供趋势图
     * 在断点标注"因何断开"（出口④的行为规格：换 evaluator_version → 断开并说明）。
     */
    public static Optional<String> breakReason(Trace previous, Trace current) {
        if (previous.equals(current)) {
            return Optional.empty();
        }
        if (!previous.evaluatorVersion().equals(current.evaluatorVersion())) {
            return Optional.of("评估器版本变化（" + previous.evaluatorVersion() + " → "
                + current.evaluatorVersion() + "），分数口径不可比");
        }
        if (!java.util.Objects.equals(previous.evaluatorModel(), current.evaluatorModel())) {
            return Optional.of("评分模型变化，分数口径不可比");
        }
        if (!java.util.Objects.equals(previous.promptHash(), current.promptHash())) {
            return Optional.of("评分提示模板变化，分数口径不可比");
        }
        return Optional.of("出题模型变化，题目内容不可比");
    }

    /**
     * 把按时间正序的报告留痕切成可比区间：返回若干段的结束下标（0 起、不含），段内相邻可比、
     * 段间在不可比处断开。报告页据此决定是否连线。
     */
    public static List<Integer> comparableSegmentEnds(List<Trace> chronologicalTraces) {
        List<Integer> segmentEnds = new ArrayList<>();
        for (int i = 1; i < chronologicalTraces.size(); i++) {
            if (breakReason(chronologicalTraces.get(i - 1), chronologicalTraces.get(i)).isPresent()) {
                segmentEnds.add(i - 1); // i-1 是一条段的末点，i 起新段
            }
        }
        if (!chronologicalTraces.isEmpty()) {
            segmentEnds.add(chronologicalTraces.size() - 1);
        }
        return segmentEnds;
    }
}
