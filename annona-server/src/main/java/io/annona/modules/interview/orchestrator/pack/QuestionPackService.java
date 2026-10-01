package io.annona.modules.interview.orchestrator.pack;

import io.annona.modules.interview.orchestrator.plan.InterviewPlan;
import io.annona.shared.question.QuestionCandidate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 组卷算法（interview-session-adr §决策 4）：plan 难度序列逐槽抽取 + 相邻回填 + 去重剔除 +
 * 池内同题干折叠。<b>无状态纯逻辑、零 IO</b>——题库读取与相似判在 {@link QuestionDedupService}
 * 与 questionbank 的 QuestionQueryService，本类输入全在参数里，golden 快照即其行为规格。
 *
 * <p>池不足时不硬凑（返回短于 totalCount 的结果 + skippedReasons）：选"用户看到 8/10 题并
 * 收到缺口说明"而非"混入不相关难度的题"——难度序列是 plan 对用户承诺的口径，失真比缺题更伤
 * 信任（否决 🅖 的 LLM 兜底出题：批 2 题目源固定为批 1 产物，内容不自产，见 AGENTS §1）。
 */
@Service
public class QuestionPackService {

    /**
     * @param plan      已校验的组卷计划
     * @param pool      ACTIVE 题目池（保持题库侧时间倒序——同难度多候选时新题优先）
     * @param dedupHits 去重命中集（命中题整体剔除）
     */
    public PackResult pack(InterviewPlan plan, List<QuestionCandidate> pool,
                           Map<UUID, StemSimilarity> dedupHits) {
        return pack(plan, pool, dedupHits, java.util.Set.of());
    }

    /**
     * 决策驱动组卷（P1c-05）：与三参重载同算法，额外把 {@code reviewIds}（planner 选定的复习题）
     * 在同难度桶内置顶优先，并<b>豁免历史去重</b>（否则复习题会被自己的去重规则吃掉）。
     * {@code reviewIds} 为空时行为与三参重载逐字节一致——既有 golden 传空集即全量兼容。
     *
     * @param reviewIds 需优先掺入的复习题 ID（按掌握度最低的历史题，可跨难度桶）
     */
    public PackResult pack(InterviewPlan plan, List<QuestionCandidate> pool,
                           Map<UUID, StemSimilarity> dedupHits, java.util.Set<UUID> reviewIds) {
        // 池内同题干折叠（去空白小写后首个存活）——局部集合，实例无可变状态
        Set<String> seenStems = new HashSet<>();
        Map<Integer, List<QuestionCandidate>> byDifficulty = new LinkedHashMap<>();
        for (QuestionCandidate q : pool) {
            // 复习题豁免历史去重：planner 选定的低分题本质就是“答过的题”，若让 90 天去重
            // 拦下它们，掺复习题就永远空转且 REMIND_REVIEW 留痕与卷面不符（interview-session-adr
            // §决策 4 的去重目的是“不出新重复题”，重练已知弱项是被授权的例外）。
            boolean dedupBlocked = dedupHits.containsKey(q.id()) && !reviewIds.contains(q.id());
            if (dedupBlocked || !seenStems.add(normalize(q.question()))) {
                continue;
            }
            byDifficulty.computeIfAbsent(q.difficulty(), k -> new ArrayList<>()).add(q);
        }
        // 复习题在同难度桶内置顶（稳定排序：reviewIds 命中者提前，其余保持题库倒序原序）
        if (!reviewIds.isEmpty()) {
            byDifficulty.values().forEach(bucket -> bucket.sort(
                java.util.Comparator.comparingInt(q -> reviewIds.contains(q.id()) ? 0 : 1)));
        }

        Set<UUID> used = new HashSet<>();
        List<UUID> chosen = new ArrayList<>();
        Map<Integer, Integer> skipCountByDifficulty = new LinkedHashMap<>();
        for (int slot = 0; slot < plan.totalCount(); slot++) {
            int target = plan.difficulties().get(slot);
            QuestionCandidate picked = pickNearest(byDifficulty, target, used);
            if (picked == null) {
                skipCountByDifficulty.merge(target, 1, Integer::sum);
            } else {
                used.add(picked.id());
                chosen.add(picked.id());
            }
        }

        List<String> skipped = new ArrayList<>();
        skipCountByDifficulty.forEach((difficulty, count) -> skipped.add(
            "难度" + difficulty + " 无可用题目（含相邻回填），跳过 " + count + " 个槽位"));
        return new PackResult(List.copyOf(chosen), List.copyOf(skipped));
    }

    /** 按 {@link PackRules#adjacentOrder} 取该槽位第一道未用候选；全空返回 null。 */
    private static QuestionCandidate pickNearest(
        Map<Integer, List<QuestionCandidate>> byDifficulty, int target, Set<UUID> used) {
        for (int difficulty : PackRules.adjacentOrder(target)) {
            for (QuestionCandidate candidate : byDifficulty.getOrDefault(difficulty, List.of())) {
                if (!used.contains(candidate.id())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** 折叠口径：去空白 + 小写——只防"复制改标点"级别的池内重复，更深相似归向量判。 */
    private static String normalize(String stem) {
        return stem.replaceAll("\\s+", "").toLowerCase();
    }
}
