package io.annona.modules.planner.mastery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 掌握度模型（设计文档 §6.2）——遗忘衰减 + 练习增益 + 置信度的<b>纯函数</b>：无 Spring、无 IO、
 * 无隐藏状态，输入全在参数里，输出由输入唯一决定（golden 即其行为规格）。
 *
 * <p>公式（逐字对齐 §6.2）：
 * <pre>
 *   decay(t) = 0.5 ^ (t / H)
 *   gain     = clamp((weighted_score - 0.5) * 2, -1, 1) * (1 + followUpWeight * depth)
 *   mastery' = clamp(mastery * (1 - lr) + (mastery * decay(t) + gain * k) * lr, 0, 1)
 *   lr       = lrBase - lrDecay * min(sample_size, lrSampleCap)   // 冷启动快、后期稳
 *   confidence = min(sample_size / confidenceDivisor, 1) * qualityWeight
 * </pre>
 *
 * <p>关键取舍（AGENTS §4"涉及取舍写进注释"）：
 * <ul>
 *   <li><b>现算不落表</b>：输入是有界小事件集（门面已截最近 10 场），指数滑动更新对整段历史
 *       重算的代价可忽略；物化 {@code mastery} 表要多一张表 + 事件增量维护 + 失效一致性，
 *       在没有真实性能压力前是净负担（planner-decision-kernel-adr 决策 3、否决表）。
 *       重新评估触发：单方向报告数 &gt; 200 或组卷 P95 实测退化。</li>
 *   <li><b>中立先验 0.5</b>：冷启动无历史时既不说"完全掌握"也不说"一无所知"，0.5 让首次
 *       练习的 lr=lrBase 步长直接把结论推向实际观测。</li>
 *   <li><b>qualityWeight 传 null → 取中性 0.5</b>：方向无学习记录是正常形态（design §6.1），
 *       掌握度纯由面试样本驱动，不因"没来自习室的质量证据"而被惩罚性压低。</li>
 * </ul>
 */
public final class MasteryModel {

    /** 中立先验：无历史时既不肯定也不否定掌握。 */
    static final double INITIAL_MASTERY = 0.5;

    private MasteryModel() {
    }

    /**
     * 计算某方向的当前掌握状态。
     *
     * @param events       该方向的有效面试事件，<b>按 at 升序</b>；调用方保证有序
     *                     （门面 {@code recentOutcomes} 是倒序，advisor 侧反转后传入）
     * @param qualityWeight 自习室质量均值 [0,1]；{@code null} = 方向无学习记录，按中性值处理
     * @param p            模型参数
     * @param asOf         决策参考时刻（衰减到此时刻；测试注入历史日期保证可复现）
     * @return 掌握状态（mastery/confidence 归一到两位小数，避免浮点尾差污染 golden）
     */
    public static MasteryView evaluate(List<MasteryEvent> events, Double qualityWeight,
                                       MasteryParams p, Instant asOf) {
        double mastery = INITIAL_MASTERY;
        int sample = 0;
        Instant last = null;
        for (MasteryEvent e : events) {
            double days = last == null ? 0 : daysBetween(last, e.at());
            double gain = gain(e, p);
            double lr = learningRate(sample, p);
            double decayed = mastery * Math.pow(0.5, days / p.halfLifeDays());
            mastery = clamp(mastery * (1 - lr) + (decayed + gain * p.gainK()) * lr, 0, 1);
            sample++;
            last = e.at();
        }
        if (last != null) {
            double days = daysBetween(last, asOf);
            mastery = clamp(mastery * Math.pow(0.5, days / p.halfLifeDays()), 0, 1);
        }
        double qw = qualityWeight == null ? p.neutralQualityWeight() : clamp(qualityWeight, 0, 1);
        double confidence = Math.min((double) sample / p.confidenceDivisor(), 1.0) * qw;
        return new MasteryView(round2(mastery), round2(confidence), sample, last);
    }

    /** 单次练习增益：分数偏差项 [-1,1] 乘追问深度放大。 */
    private static double gain(MasteryEvent e, MasteryParams p) {
        double base = clamp((e.weightedScore() - 0.5) * 2, -1, 1);
        return base * (1 + p.followUpWeight() * e.followUpDepth());
    }

    /** 学习率随样本数衰减，触底于 lrSampleCap；下限 0（防参数写错变负步长）。 */
    private static double learningRate(int sample, MasteryParams p) {
        return Math.max(0, p.lrBase() - p.lrDecay() * Math.min(sample, p.lrSampleCap()));
    }

    /** 间隔天数（UTC 天粒度，向下取整）；乱序导致的负值归 0，避免除零/负指数。 */
    private static double daysBetween(Instant from, Instant to) {
        long days = ChronoUnit.DAYS.between(from, to);
        return Math.max(0, days);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
