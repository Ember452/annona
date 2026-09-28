package io.annona.modules.retrieval.hybrid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import io.annona.spi.dto.RetrievalHit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RRF 融合纯函数单测（表驱动）。这些数字是检索指标的分母，必须先自证：
 * K、名次起点、归一化口径任一改动都会同时改变 Recall@K 与前端置信度显示的含义。
 */
@DisplayName("RrfFusion：融合名次与归一化分数")
class RrfFusionTest {

    @Test
    @DisplayName("同一块在两条通道都排第一 → 归一化到 1.0")
    void bothChannelsFirstSaturatesToOne() {
        RetrievalHit hit = hit("c1", 0.9);

        List<RetrievalHit> fused = RrfFusion.fuse(List.of(List.of(hit), List.of(hit)), 4);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).score()).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    @DisplayName("只在单通道排第一 → 归一化上限是 0.5（不是满分，也不是低置信）")
    void singleChannelFirstHalves() {
        RetrievalHit hit = hit("c1", 0.9);

        List<RetrievalHit> fused = RrfFusion.fuse(List.of(List.of(hit), List.of()), 4);

        assertThat(fused.get(0).score()).isCloseTo(0.5, offset(1e-9));
    }

    @Test
    @DisplayName("公式逐项核对：rank 从 1 起、K=60、跨通道累加")
    void followsReciprocalRankFormula() {
        // 目标块：通道一第 2 名 + 通道二第 1 名（rank = 下标 + 1，不是下标）
        RetrievalHit target = hit("target", 0.5);
        RetrievalHit onlyFirst = hit("only-first", 0.7);
        RetrievalHit onlySecond = hit("only-second", 0.6);
        List<RetrievalHit> channelOne = List.of(onlyFirst, target);
        List<RetrievalHit> channelTwo = List.of(target, onlySecond);

        List<RetrievalHit> fused = RrfFusion.fuse(List.of(channelOne, channelTwo), 4);

        double expected = (1.0 / (RrfFusion.K + 2) + 1.0 / (RrfFusion.K + 1)) / RrfFusion.MAX_SCORE;
        assertThat(fused.get(0).chunkId()).isEqualTo("target");
        assertThat(fused.get(0).score()).isCloseTo(expected, offset(1e-9));
        // 本例三块分数各不相同（跨两通道的 target 最高）：降序必须严格，否则排序不可解释
        assertThat(fused).extracting(RetrievalHit::chunkId)
            .containsExactly("target", "only-first", "only-second");
    }

    @Test
    @DisplayName("并集去重后按分数降序；同分时先出现的通道排在前面（结果可复现）")
    void unionsDedupesAndBreaksTiesByChannelOrder() {
        RetrievalHit x = hit("x", 0.9);
        RetrievalHit y = hit("y", 0.8);
        // 两条通道各给一份 x、y，但顺序相反：融合后分数完全相同，靠通道优先序定先后
        List<RetrievalHit> fused = RrfFusion.fuse(
            List.of(List.of(x, y), List.of(y, x)), 4);

        assertThat(fused).hasSize(2);
        assertThat(fused).extracting(RetrievalHit::chunkId).containsExactly("x", "y");
        assertThat(fused.get(0).score()).isEqualTo(fused.get(1).score());
    }

    @Test
    @DisplayName("topK 截断生效，且空通道不参与融合")
    void truncatesToTopK() {
        List<RetrievalHit> channel = List.of(hit("a", .9), hit("b", .8), hit("c", .7));

        List<RetrievalHit> fused = RrfFusion.fuse(List.of(List.of(), channel), 2);

        assertThat(fused).hasSize(2);
        assertThat(fused).extracting(RetrievalHit::chunkId).containsExactly("a", "b");
    }

    @Test
    @DisplayName("topK <= 0 直接拒绝：不允许「融合出一个空截断」这种静默行为")
    void rejectsNonPositiveTopK() {
        assertThatThrownBy(() -> RrfFusion.fuse(List.of(List.of(hit("a", 1.0))), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("topK");
    }

    @Test
    @DisplayName("代表块的 docId 与 snippet 取先到者（语义通道优先展示它的片段）")
    void keepsFirstOccurrenceAsRepresentative() {
        RetrievalHit fromSemantic = new RetrievalHit("doc-1", "c1", "语义通道的片段", 0.9);
        RetrievalHit fromKeyword = new RetrievalHit("doc-1", "c1", "关键词通道的片段", 0.8);

        List<RetrievalHit> fused = RrfFusion.fuse(
            List.of(List.of(fromSemantic), List.of(fromKeyword)), 4);

        assertThat(fused.get(0).snippet()).isEqualTo("语义通道的片段");
    }

    private static RetrievalHit hit(String chunkId, double relevance) {
        return new RetrievalHit("doc-1", chunkId, "snippet-" + chunkId, relevance);
    }
}
