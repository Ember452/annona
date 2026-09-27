package io.annona.spi.model;

import java.util.List;

/**
 * Embedding 扩展点（knowledge-ingestion-adr §决策 5）。{@link ModelProvider} javadoc
 * 预留的三个独立扩展点之一，P1a-05 随知识入库管线落地——与 chat 的配置、配额、失败
 * 语义都不同，刻意不并入 chat 接口。
 *
 * <p>实现约定：返回顺序与入参一一对应、等长等维；网络与限流异常包装成运行时业务异常
 * （实现方用 {@code io.annona.common.exception.BusinessException}，与 chat 同一口径），
 * 禁止裸抛；产出维度必须与 {@link #dimensions()} 一致，且与部署的向量列维度匹配。
 *
 * <p>与 ModelProvider 相同：本模块是对外发布契约 jar，刻意不依赖 annona-common，
 * javadoc 不写无法解析的 {@code @link}。
 */
public interface EmbeddingProvider {

    /** Provider 唯一名；READY 时落 {@code kb_doc.embedding_model}，检索端按它过滤。 */
    String name();

    /** 本 provider 产出向量的固定维度。 */
    int dimensions();

    /**
     * 批量嵌入一段文本。实现方可内部分批（按供应商单批上限），调用方只管给全量。
     *
     * @param texts 非空文本列表；空白文本由调用方保证不存在（分块器已过滤）
     */
    List<float[]> embed(List<String> texts);
}
