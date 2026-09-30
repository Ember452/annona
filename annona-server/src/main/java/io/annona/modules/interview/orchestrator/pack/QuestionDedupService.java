package io.annona.modules.interview.orchestrator.pack;

import io.annona.common.search.Tokenizer;
import io.annona.modules.interview.orchestrator.session.InterviewAnswerRepository;
import io.annona.shared.question.QuestionCandidate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 历史去重双判（interview-session-adr §决策 4）：向量判 = 候选行与该用户近
 * {@link PackRules#HISTORY_WINDOW_DAYS} 天已作答题目的库内 cosine（SQL 直出，候选 embedding
 * 缺失时该行天然不参与——降级关键词判，M3）；关键词判 = Tokenizer 切词前
 * {@link PackRules#KEYWORD_TOKENS} 个实词被任一历史题干全包含。
 *
 * <p>阈值判定放本服务而非 SQL：阈值是决策口径（改值要同步 ADR 与 golden），SQL 只出原始
 * 相似值——把口径钉在查询串里会让"改一个常量"变成"改两条 SQL 加一个测试夹具"。
 */
@Service
public class QuestionDedupService {

    private final InterviewAnswerRepository answerRepository;
    private final Tokenizer tokenizer;

    public QuestionDedupService(InterviewAnswerRepository answerRepository, Tokenizer tokenizer) {
        this.answerRepository = answerRepository;
        this.tokenizer = tokenizer;
    }

    /** 返回命中重复的候选（key=候选 id）；未命中（cosine 低且关键词不全包含）不入图。 */
    @Transactional(readOnly = true)
    public Map<UUID, StemSimilarity> findDedupHits(UUID userId, UUID directionId,
                                                   List<QuestionCandidate> pool) {
        Instant since = Instant.now().minus(PackRules.HISTORY_WINDOW_DAYS, ChronoUnit.DAYS);
        Map<UUID, Double> maxCosine = new LinkedHashMap<>();
        for (Object[] row : answerRepository.maxCosineToAnswered(userId, directionId, since)) {
            maxCosine.put((UUID) row[0], ((Number) row[1]).doubleValue());
        }
        List<String> historyStems = answerRepository.recentAnsweredStems(userId, directionId, since)
            .stream().map(String::toLowerCase).toList();

        Map<UUID, StemSimilarity> hits = new LinkedHashMap<>();
        for (QuestionCandidate candidate : pool) {
            double cosine = maxCosine.getOrDefault(candidate.id(), 0.0);
            boolean keywordHit = keywordDup(candidate.question(), historyStems);
            if (cosine >= PackRules.COSINE_EXCLUDE || keywordHit) {
                hits.put(candidate.id(), new StemSimilarity(candidate.id(), cosine, keywordHit));
            }
        }
        return hits;
    }

    private boolean keywordDup(String stem, List<String> historyStems) {
        if (historyStems.isEmpty()) {
            return false;
        }
        String[] tokens = tokenizer.tokenize(stem).trim().split("\\s+");
        if (tokens.length < PackRules.KEYWORD_TOKENS || tokens[0].isEmpty()) {
            return false;   // 实词不足不做关键词判（防一两个通用词误杀，PackRules.Javadoc）
        }
        for (String history : historyStems) {
            boolean allContained = true;
            for (int i = 0; i < PackRules.KEYWORD_TOKENS; i++) {
                if (!history.contains(tokens[i])) {
                    allContained = false;
                    break;
                }
            }
            if (allContained) {
                return true;
            }
        }
        return false;
    }
}
