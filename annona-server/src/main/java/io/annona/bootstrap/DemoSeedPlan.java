package io.annona.bootstrap;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * demo 合成的时间计划（纯函数，可单测）。{@code DemoSeedRunner} 据它把 6 周的历史摊到日期上，
 * 保证 seed 结果确定、可复现（同一 weeks → 同一批日期），且面试侧留"足够新但有衰减"的梯度：
 * 最近一周有练习、更早的场次间隔拉大 → 让 FORGETTING/WEAK 规则在 demo 里真实触发。
 */
final class DemoSeedPlan {

    private DemoSeedPlan() {
    }

    /**
     * 生成 {@code sessions} 场面试的日期（倒序：越靠前越近）。
     *
     * @param today    参考日（注入以便测试复现）
     * @param weeks    覆盖周数
     * @param sessions 场次数（≥1）
     */
    static List<LocalDate> interviewDates(LocalDate today, int weeks, int sessions) {
        List<LocalDate> dates = new ArrayList<>();
        int spanDays = Math.max(1, weeks * 7);
        for (int i = 0; i < sessions; i++) {
            // 第 0 场距今 2 天（留出"刚练过"的最近点），之后按跨度均匀回推
            int daysAgo = 2 + (int) Math.round((double) i * spanDays / Math.max(1, sessions));
            dates.add(today.minusDays(daysAgo));
        }
        return dates;
    }

    /**
     * 生成学习会话的日期（覆盖窗口内每周 3 天，带 VERIFIED/PARTIAL/SELF_REPORTED 混合的索引）。
     *
     * @param today 参考日
     * @param weeks 覆盖周数
     */
    static List<LocalDate> studyDates(LocalDate today, int weeks) {
        List<LocalDate> dates = new ArrayList<>();
        for (int w = 0; w < weeks; w++) {
            for (int dow : new int[] {1, 3, 5}) {
                dates.add(today.minusWeeks(w).minusDays(dow));
            }
        }
        return dates;
    }

    /** 幂等判据：demo 用户已存在则整个 seed 跳过（重复执行不叠加数据）。 */
    static boolean shouldSkip(boolean demoUserExists) {
        return demoUserExists;
    }
}
