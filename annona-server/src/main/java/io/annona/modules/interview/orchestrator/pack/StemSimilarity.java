package io.annona.modules.interview.orchestrator.pack;

import java.util.UUID;

/**
 * 单题的去重判据（interview-session-adr §决策 4 的双判结果载体）。
 *
 * @param questionId  候选 ID
 * @param cosine      与该用户近 {@link PackRules#HISTORY_WINDOW_DAYS} 天已作答题目的最大
 *                  余弦相似；embedding 缺失或非在途时为 0
 * @param keywordHit  关键词判是否命中（前 5 实词全包含）
 */
public record StemSimilarity(UUID questionId, double cosine, boolean keywordHit) {
}
