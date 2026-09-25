package io.annona.spi.fake;

import io.annona.spi.dto.RetrievalHit;
import io.annona.spi.dto.RetrievalQuery;
import io.annona.spi.retrieval.Retriever;
import java.util.Collections;
import java.util.List;

/**
 * {@link Retriever} 的内存实现。默认返回空集，等价于"知识库里没找到"。
 *
 * <p>用途：
 * <ul>
 *   <li>P1a-05 之前，retrieval 通道还没实现；qa / interview 想拿一个可注入的
 *       Retriever 让上层业务代码先跑起来，用这个。</li>
 *   <li>测试里若需要"命中若干块"，用 {@link #FakeRetriever(List)} 传预置结果。</li>
 * </ul>
 *
 * <p>不接 PG / pgvector / ES，任何 profile 下都可安全装配。
 */
public final class FakeRetriever implements Retriever {

    /** 与 {@code annona.retrieval.backend} 的取值 {@code fake} 对齐。 */
    public static final String BACKEND = "fake";

    private final List<RetrievalHit> preset;

    public FakeRetriever() {
        this(Collections.emptyList());
    }

    public FakeRetriever(List<RetrievalHit> preset) {
        this.preset = List.copyOf(preset);
    }

    @Override
    public String backend() {
        return BACKEND;
    }

    @Override
    public List<RetrievalHit> retrieve(RetrievalQuery query) {
        // 真实实现要按 topK 截断；fake 直接返整份预置结果，测试自己控长度
        return preset;
    }
}
