package io.annona.modules.retrieval.hybrid;

import io.annona.spi.dto.RetrievalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）纯函数——只依赖名次，不依赖各通道分数的可比性。
 *
 * <p>参数取值：{@code K=60}、排名从 1 起、{@code score = Σ 1/(K + rank)}，沿用 🅜
 * {@code HybridSearchService} 的实测口径（本项目"v1 用可工作的最简起点与借鉴参数，
 * 调优等真实反馈"，见开发计划 §实现深度）；不引入加权系数（没被要求，且权重在没有
 * 真实问答数据前无从标定）。
 *
 * <p>并列名次按"先出现的通道在前"稳定排序（LinkedHashMap 的插入序），
 * 这样同一批候选两次融合的结果一致，评测数字才可复现。
 */
public final class RrfFusion {

    /** RRF 平滑常数（沿用借鉴值）。 */
    public static final int K = 60;

    /** 归一化分母：同一块在<b>两条通道都排第一</b>时的理论上限。 */
    public static final double MAX_SCORE = 2.0 / (K + 1);

    private RrfFusion() {
    }

    /**
     * 融合各通道的有序候选。
     *
     * @param rankedChannels 每个通道内部已按相关性降序排好的列表（空列表表示该通道未参与，
     *                       例如关键词通道对纯符号查询无命中）；<b>通道顺序影响并列时的先后</b>
     * @param topK           截断长度，必须 &gt; 0
     * @return 按归一化分数降序的命中；{@code score} 落在 [0,1]，口径是
     *         "双通道均第一 ≈ 1.0，仅单通道第一 ≈ 0.5"（{@code RetrievalHit} 契约）
     */
    public static List<RetrievalHit> fuse(List<List<RetrievalHit>> rankedChannels, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须 > 0，得到 " + topK);
        }
        Map<String, Acc> merged = new LinkedHashMap<>();
        for (List<RetrievalHit> channel : rankedChannels) {
            for (int rank = 0; rank < channel.size(); rank++) {
                RetrievalHit hit = channel.get(rank);
                Acc acc = merged.computeIfAbsent(hit.chunkId(), id -> new Acc(hit));
                acc.score += 1.0 / (K + rank + 1);
            }
        }
        List<RetrievalHit> fused = new ArrayList<>(merged.size());
        for (Acc acc : merged.values()) {
            fused.add(new RetrievalHit(acc.representative.docId(), acc.representative.chunkId(),
                acc.representative.snippet(), acc.score / MAX_SCORE));
        }
        fused.sort(Comparator.comparingDouble(RetrievalHit::score).reversed());
        return fused.size() > topK ? List.copyOf(fused.subList(0, topK)) : fused;
    }

    /** 融合累计器：代表块取首次出现的那份（snippet 与 docId 都以先到者为准）。 */
    private static final class Acc {
        private final RetrievalHit representative;
        private double score;

        Acc(RetrievalHit representative) {
            this.representative = representative;
        }
    }
}
