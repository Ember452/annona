package io.annona.common.model;

import java.util.List;

/**
 * 流式 chat 端口（qa-streaming-adr）。与同步的 {@code ModelProvider}（annona-spi）
 * 互为独立端口：失败语义与协议演进节奏不同，但同一实现类可以同时实现两者（本仓的
 * OpenAI 兼容实现如此）。
 *
 * <p>契约：实现必须恰好以 {@code onComplete} 或 {@code onError} 之一终止；{@code onDelta}
 * 允许零次（上游立即失败即转 {@code onError}）；终态回调之后不得再有任何回调。
 */
public interface StreamingChatProvider {

    /** Provider 唯一名（模型 id），与同步实现同口径，供用量归属的 model 列。 */
    String name();

    /** 供应通道标识（配置的 provider 枚举值），供用量归属的 provider 列（TD-03，
     * 与 {@code ModelProvider.channel} 同口径；同一实现类双端口时两处返回同一配置值）。 */
    String channel();

    /**
     * 单次流式调用的服务端超时建议值（毫秒）：消费方（SSE 兜底）按它派生而非另定
     * 魔数（TD-04）。实现方无此概念时返 0，调用方自取回退默认。
     */
    long streamTimeoutMillis();

    /**
     * 发起一次流式 chat 并<b>阻塞</b>到流终态（onComplete/onError）。选阻塞式而非返回
     * 响应式流：common 不引 Reactor，调用方在自己的执行器线程上调用本方法，回调线程
     * 只做轻量转发（见 {@link ChatStreamListener} 的线程约束）。
     *
     * @param messages 对话消息（P1a-08 问答为 system + user 两条；多轮历史属 P1b 演进，
     *                 端口形状按需扩展，加参不改语义）
     */
    void streamChat(List<ChatMessage> messages, ChatStreamListener listener);
}
