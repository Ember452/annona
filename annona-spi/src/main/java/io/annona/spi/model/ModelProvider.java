package io.annona.spi.model;

import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.dto.ModelResponse;
import java.util.List;

/**
 * 模型网关扩展点。屏蔽供应商差异（OpenAI 兼容 / DashScope / 本地推理），
 * 业务侧统一走本接口。
 *
 * <p>流式响应已按 docs/specs/2026-09-28-qa-streaming-adr.md 落在 annona-common 的
 * {@code StreamingChatProvider}（内部解耦端口：无外部实现方需求，且本 SPI 是对外发布的
 * 契约 jar，不冻结易变的流式协议；本模块不依赖 common，故无法写 {@code @link}）。
 * Embedding 是独立 SPI 扩展点（{@code EmbeddingProvider}），Rerank 属 P1b 候选。
 * 本 SPI 只承诺<b>同步非流式</b> chat 语义。
 */
public interface ModelProvider {

    /** Provider 唯一名（模型 id），用于配置路由（{@code annona.model.default}）与用量归属。 */
    String name();

    /**
     * 供应通道标识（如 {@code openai-compatible} / {@code fake}，即配置的 provider 枚举值）。
     * 与 {@link #name()}（模型 id）是两个维度：同一通道可换模型，成本归因要按通道聚合
     * （{@code token_usage.provider} 列，llmprovider-metering-adr 批 3 修订）。实现方全在
     * 仓内，随批 3 usage 契约变更直接断加入，不提供 default。
     */
    String channel();

    /**
     * 同步调用一次 chat。网络与限流异常必须包装成业务异常（实现方用
     * {@code io.annona.common.exception.BusinessException}），禁止裸抛。
     *
     * <p>这里<b>不写</b> {@code @link}：本模块是对外发布的契约 jar，刻意不依赖
     * {@code annona-common}（否则 Spring 会随传递依赖进入发布物），因此也不能引用
     * 它的类型——否则生成 javadoc jar 时会出现无法解析的链接。
     */
    ModelResponse chat(List<ModelChatMessage> messages, ModelOptions options);
}
