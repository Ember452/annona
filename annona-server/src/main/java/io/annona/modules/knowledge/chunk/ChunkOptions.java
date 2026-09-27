package io.annona.modules.knowledge.chunk;

/**
 * 分块阈值集（knowledge-ingestion-adr §决策 4）。
 *
 * <p>取值依据（借 🅢 chunk.ts 实测值，按<b>字符</b>计而非 token——token 口径要引入
 * 分词器依赖，已被 ADR 否决；800 字符远低于 embedding 输入上限，中文 1 字约 1–2 token）：
 * <ul>
 *   <li>{@code sectionChunkSize}=800：单节不超过此长度即整节成片，超过则按此窗口滑窗；
 *       同时是句末断点搜索窗口宽度；</li>
 *   <li>{@code overlap}=50：相邻片共享尾部 50 字符，保住跨片边界的句子上下文；</li>
 *   <li>{@code sentenceBreakRatio}=0.6：句末断点必须落在窗口 60% 位置之后——既避免
 *       硬切句子，也避免在窗口开头就近断开产生碎片。</li>
 * </ul>
 * 调优推迟到 P1a-09 有 Recall@K / MRR 实测数字之后（ADR §何时重新评估）。
 *
 * @param sectionChunkSize   节内软上限 / 窗口宽度（字符）
 * @param overlap            相邻片重叠（字符），必须小于 sectionChunkSize
 * @param sentenceBreakRatio 句末断点最小位置比例，(0,1) 开区间
 */
public record ChunkOptions(int sectionChunkSize, int overlap, double sentenceBreakRatio) {

    public static final ChunkOptions DEFAULTS = new ChunkOptions(800, 50, 0.6);

    public ChunkOptions {
        // 下限只为挡退化配置（size=0 会让窗口永远为空）；小窗口是 golden 测试的合法参数，不设更高地板
        if (sectionChunkSize < 1) {
            throw new IllegalArgumentException("sectionChunkSize 必须为正，实际 " + sectionChunkSize);
        }
        if (overlap < 0 || overlap >= sectionChunkSize) {
            throw new IllegalArgumentException(
                "overlap 必须 ∈ [0, sectionChunkSize)，实际 overlap=" + overlap + ", size=" + sectionChunkSize);
        }
        if (sentenceBreakRatio <= 0 || sentenceBreakRatio >= 1) {
            throw new IllegalArgumentException("sentenceBreakRatio 必须在 (0,1)，实际 " + sentenceBreakRatio);
        }
    }
}
