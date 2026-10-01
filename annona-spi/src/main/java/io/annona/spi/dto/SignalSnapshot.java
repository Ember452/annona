package io.annona.spi.dto;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 一段时间窗口内的学习信号快照。planner 的唯一输入源，字段设计对齐可解释面板的展示需要。
 *
 * <p><b>修订 4</b>（planner-decision-kernel-adr）：增加方向维度——direction ADR 修订 3 判定
 * 用户级聚合无法表达"信号按 direction_id 隔离、相交才增强"，本修订补齐。方向口径：
 * {@code readDirectional} 返回的快照只含被请求的单一方向（{@code directionals.size()==1}），
 * 用户级 {@code read} 聚合全部方向。窗口语义：{@code [from, to]} 闭区间<b>只作用于学习侧</b>；
 * 面试侧事件集按条数取最近（见 {@code recentSessions}），历史日期注入保证 golden 可复现。
 * 修订 1（planner-decision-kernel-adr）拆清了这两个口径。
 *
 * @param userId            用户主键
 * @param from              窗口起始日（含）
 * @param to                窗口结束日（含）
 * @param totalStudy        累计有效学习时长（VERIFIED + PARTIAL 折算）；修订 4 起为派生值，
 *                          方向明细以 {@code directionals} 为准
 * @param completionPercent 计划完成率（0–100）。数据源是 plan_task 表——该表 P2-06 才建，
 *                          <b>P1c 期间恒为 {@code null}</b>（无数据源与"值为 0"是两回事，
 *                          面板据此不得把"无计划"渲染成"完成率 0%"）
 * @param sampleSize        有效样本量：窗口内该口径下的面试场数，供 {@code planner/guard}
 *                          判定是否够做难度调整。<b>只计有非降级分的场次</b>，与 {@code directionals}
 *                          的 {@code avgScore} 同分母（修订 1）——面板里“近 N 场均分 X”的 N 必须是 X 的真分母
 * @param directionals      按方向聚合的信号（修订 4）；无数据时为空列表，禁止 null
 * @param recentSessions    最近若干场的逐场结果（时间倒序、<b>上限 10 场、不受 {@code from/to}
 *                          窗口限制</b>）——掌握度公式的事件序列输入与 VERSION_BASELINE 的留痕比对
 *                          都从这里取，快照因而是自含可复现的。不按窗口截断是刻意的（修订 1）：
 *                          遗忘曲线必须看得到“久不练”的历史，否则半衰期 21 天的衰减量永远被截在窗口天数以内
 */
public record SignalSnapshot(
    String userId,
    LocalDate from,
    LocalDate to,
    Duration totalStudy,
    Integer completionPercent,
    int sampleSize,
    List<DirectionSignal> directionals,
    List<SessionOutcome> recentSessions) {

    /** 紧凑构造器：防御性拷贝 + 非空约束（对外契约 jar，同 InterviewPlan 的不可变理由）。 */
    public SignalSnapshot {
        directionals = List.copyOf(directionals);
        recentSessions = List.copyOf(recentSessions);
    }

    /** 取指定方向的信号；该方向无记录时返回空——"方向不相交"的正常形态，不是错误。 */
    public DirectionSignal directional(String directionId) {
        return directionals.stream()
            .filter(d -> d.directionId().equals(directionId))
            .findFirst()
            .orElse(null);
    }
}
