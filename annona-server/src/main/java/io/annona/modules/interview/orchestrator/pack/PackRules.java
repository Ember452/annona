package io.annona.modules.interview.orchestrator.pack;

import java.util.ArrayList;
import java.util.List;

/**
 * 组卷算法的阈值与规则常量（决策算法 ADR 项：每个取值的口径与依据写在这里，改值必须同步
 * interview-session-adr §决策 4 与 golden 快照）。
 */
public final class PackRules {

    /**
     * 向量判排除阈值：cosine ≥ 0.92 视为重复。取值是偏保守的经验初值（尚未用本题池实测过
     * 相似分布）：选高的这一侧而非低的——误杀一道新题只少一道可出题，放过一道重复题则是
     * 用户“刷两场撞题”的直观差评。校准触发：批 3 首次 RAG 评测跑同池相似分布后回写实测值
     * （interview-session-adr §何时重新评估）。
     */
    public static final double COSINE_EXCLUDE = 0.92;

    /**
     * 关键词判的实词个数与"全包含"口径：候选题干切词取前 5 个，某历史题干同时包含全部 5 个
     * 才算重复。取 5 的依据：题干 ≤300 字，5 个实词同现即近似复读；部分包含误杀率高——
     * 技术关键词跨题复用是常态（"Redis""持久化"会出现在十几道题里）。
     * 实词不足 5 个的短题干不做关键词判（宁可少判，不做一两个通用词就误杀的判断）。
     */
    public static final int KEYWORD_TOKENS = 5;

    /**
     * 历史窗口 90 天：备考周期典型 4–8 周，90 天覆盖“上一轮备考”；更早的题用户大概率已
     * 遗忘，重复反而是复习机会。🅖 用“最近 10 场会话”不做时间口径——会话频率
     * 因用户差异大，时间窗对“连续两场撞题”的防护更稳定。
     */
    public static final int HISTORY_WINDOW_DAYS = 90;

    /** 难度相邻回填的取值域（与 chk_question_difficulty 一致）。 */
    public static final int MIN_DIFFICULTY = 1;
    public static final int MAX_DIFFICULTY = 5;

    private PackRules() {
    }

    /**
     * 槽位难度 {@code target} 的回填顺序：先本难度，再近邻交错向外（3 → 3,2,4,1,5）。
     * 近邻优先 = 难度分布失真最小；先低后高 = 偏保守（宁送略易，不砸心态）。
     */
    public static List<Integer> adjacentOrder(int target) {
        List<Integer> order = new ArrayList<>();
        order.add(target);
        for (int delta = 1; delta <= MAX_DIFFICULTY - MIN_DIFFICULTY; delta++) {
            int lower = target - delta;
            int higher = target + delta;
            if (lower >= MIN_DIFFICULTY) {
                order.add(lower);
            }
            if (higher <= MAX_DIFFICULTY) {
                order.add(higher);
            }
        }
        return order;
    }
}
