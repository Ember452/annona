package io.annona.spi.retrieval;

import io.annona.spi.dto.RetrievalHit;
import io.annona.spi.dto.RetrievalQuery;
import java.util.List;

/**
 * 检索后端扩展点。默认实现 {@code PgVectorRetriever}（PG + pgvector + tsvector + pg_trgm），
 * 允许第三方仓库通过依赖 {@code annona-spi} 提供 ES / Milvus / 自研向量库实现。
 *
 * <p>RRF 融合、重排、TopK 自适应在 {@code modules/retrieval/hybrid} 里做，
 * SPI 只承诺"给一个查询，返回一组按分数降序的命中块"。
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
