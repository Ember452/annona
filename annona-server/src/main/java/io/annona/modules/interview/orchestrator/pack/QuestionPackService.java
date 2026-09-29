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
        // 池内同题干折叠（去空白小写后首个存活）——局部集合，实例无可变状态
        Set<String> seenStems = new HashSet<>();
        Map<Integer, List<QuestionCandidate>> byDifficulty = new LinkedHashMap<>();
        for (QuestionCandidate q : pool) {
            if (dedupHits.containsKey(q.id()) || !seenStems.add(normalize(q.question()))) {
                continue;
            }
            byDifficulty.computeIfAbsent(q.difficulty(), k -> new ArrayList<>()).add(q);
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
