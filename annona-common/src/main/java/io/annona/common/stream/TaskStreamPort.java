package io.annona.common.stream;

import java.util.Map;

/**
 * 任务流端口：生产 {@link #send} + 消费 {@link #consume}（knowledge-ingestion-adr §决策 8）。
 *
 * <p>消息瘦身约定（借 🅖）：payload 只携带业务 ID 与 {@link #RETRY_COUNT_FIELD}
 * 重试计数，正文一律由消费者按 ID 回查——流里没有大对象，消息大小恒定。
 * 业务侧重试上限（达限判死）由 handler 决定，端口只负责按结论 ACK / 重投。
 */
public interface TaskStreamPort {

    /** 重试计数的消息字段名（首次投递为 "0"）。 */
    String RETRY_COUNT_FIELD = "retryCount";

    /**
     * @return 是否投递成功；false = Redis 不可用等投递失败，调用方应把业务实体标记失败，
     *         交恢复调度兜底（PENDING 超时补投）
     */
    boolean send(String streamKey, Map<String, String> payload);

    /**
     * 注册一个消费者循环（独立线程，随应用启停；多实例部署由消费组语义分摊）。
     * pending 认领（其他实例崩溃后遗留的未 ACK 消息）由端口按 spec 的空闲阈值自动接管。
     */
    void consume(ConsumerSpec spec, TaskMessageHandler handler);

    /**
     * @param streamKey      流 key（业务自定，如 {@code knowledge:vectorize:stream}）
     * @param group          消费组名（同一业务唯一）
     * @param consumerName   消费者名（实例内唯一即可，惯例：前缀 + 随机后缀）
     * @param batchSize      每轮读取条数
     * @param pollIntervalMs 无新消息时的阻塞读超时（同时是循环空转节奏）
     * @param pendingIdleMs  pending 消息认领的空闲阈值
     */
    record ConsumerSpec(String streamKey, String group, String consumerName,
                        int batchSize, int pollIntervalMs, long pendingIdleMs) {

        /** 默认值借 🅖 实测：批量 10、阻塞读 1s、pending 空闲 5min。 */
        public static ConsumerSpec of(String streamKey, String group, String consumerName) {
            return new ConsumerSpec(streamKey, group, consumerName, 10, 1000, 5 * 60 * 1000L);
        }
    }

    /**
     * 单条消息的处理结论。
     * <ul>
     *   <li>{@code ACK}：成功或"无需处理"（实体已删 / 已终态），确认丢弃；</li>
     *   <li>{@code RETRY}：可重试失败，端口把 retryCount+1 重新入队并 ACK 原消息；</li>
     *   <li>{@code DEAD}：重试达限或不可恢复，业务实体已被判失败，确认丢弃。</li>
     * </ul>
     */
    enum Outcome { ACK, RETRY, DEAD }

    /** 抛出异常等价于返回 {@code RETRY}（端口兜底转义），便于业务侧只写正常路径。 */
    interface TaskMessageHandler {

        /**
         * @param msgId      流消息 ID（重投后变化；仅用于日志关联）
         * @param payload    消息字段（含 retryCount）
         * @param retryCount 解析后的重试计数（首次为 0）
         */
        Outcome handle(String msgId, Map<String, String> payload, int retryCount);
    }
}
