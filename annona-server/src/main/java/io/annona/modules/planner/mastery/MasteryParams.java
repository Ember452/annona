package io.annona.modules.planner.mastery;

/**
 * 掌握度模型的参数（§6.2 公式的常量集合）。值由 {@code PlannerProperties} 从
 * {@code application.yaml} 注入后传入——{@link MasteryModel} 保持无 Spring 依赖的纯函数，
 * 便于 golden 测试直接构造参数复现。
 *
 * @param halfLifeDays          遗忘半衰期 H（天）：经过 t 天掌握度乘 {@code 0.5^(t/H)}。
 *                              默认 21（知识型记忆，取值参考艾宾浩斯遗忘曲线工程化常用区间；
 *                              非精确科学常数，靠 P1c-08 A/B 校准）
 * @param gainK                 单次练习增益的标度系数：{@code gain * k} 加到掌握度上。
 * @param followUpWeight        追问深度增益因子：每层追问把该次增益放大 {@code 1 + 本值*depth}
 *                              （答对追问比答对主问题更能证明掌握，默认 0.15）
 * @param lrBase                学习率基数（冷启动时样本少、步长大）
 * @param lrDecay               每增加一个样本，学习率衰减量：{@code lr = lrBase - lrDecay*min(n,cap)}
 * @param lrSampleCap           学习率衰减的样本上限（超过后 lr 触底，后期求稳）
 * @param confidenceDivisor     置信度饱和所需样本数：{@code min(n/本值,1)}
 * @param neutralQualityWeight  方向无学习记录时的质量权重（中性 0.5）——掌握度纯由面试样本驱动
 */
public record MasteryParams(
    double halfLifeDays,
    double gainK,
    double followUpWeight,
    double lrBase,
    double lrDecay,
    int lrSampleCap,
    int confidenceDivisor,
    double neutralQualityWeight) {
}
