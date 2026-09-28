package io.annona.spi.retrieval;

import io.annona.spi.dto.RetrievalHit;
import io.annona.spi.dto.RetrievalQuery;
import java.util.List;

/**
 * 检索后端扩展点。默认实现 {@code PgVectorRetriever} 是<b>全含后端</b>：语义通道（pgvector HNSW）、
 * 关键词通道（{@code tsv} + {@code pg_trgm} 零参数兜底）、RRF 融合均在其内完成，
 * 改 {@code annona.retrieval.backend} 即整套替换检索机器。第三方依赖 {@code annona-spi}
 * 可提供 ES / Milvus / 自研向量库实现（届时 BM25 + KNN + 融合一并由该实现负责）。
 *
 * <p>{@code modules/retrieval/hybrid} 只放<b>与后端无关的纯函数</b>（RRF 融合算法及其表驱动单测），
 * 不承担编排——否则自定义后端要么重实融合、要么被强迫依赖 annona 的内部结构。
 *
 * <p>SPI 只承诺"给一个查询，返回一组按分数降序的命中块"。查询改写、TopK 自适应、
 * 相似度阈值、联网兜底属上层策略，本接口<b>不承诺也不预留</b>。
 */
public interface Retriever {

    /** 后端标识，用于 {@code annona.retrieval.backend} 路由。 */
    String backend();

    /**
     * 执行一次检索。<b>实现必须返回非 {@code null} 列表</b>，无命中时返回空集；
     * 底层异常包装为 BusinessException。
     */
    List<RetrievalHit> retrieve(RetrievalQuery query);
}
