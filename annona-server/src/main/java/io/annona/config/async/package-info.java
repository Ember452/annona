/**
 * 异步与线程池装配：四类线程池（通用 / AI-IO / CPU / 查询）+ 未来的 Redis Stream 容器注册。
 *
 * <p>Bean 一览（本包当前提供）：
 * <ul>
 *   <li>{@code generalExecutor} {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor}
 *       —— 通用异步（Spring {@code @Async} 默认走这里）</li>
 *   <li>{@code aiIoExecutor} {@link java.util.concurrent.ExecutorService}
 *       —— LLM HTTP 阻塞调用；拒绝策略 {@code AbortPolicy} 避免上游慢导致雪崩</li>
 *   <li>{@code cpuExecutor} {@link java.util.concurrent.ExecutorService}
 *       —— 分块 / 加密 / 结构化输出解析等纯计算；线程数按 {@code availableProcessors()} 定尺</li>
 *   <li>{@code queryExecutor} {@link java.util.concurrent.ExecutorService}
 *       —— 只读聚合（热力图、趋势）</li>
 * </ul>
 *
 * <p>硬约束（AGENTS.md §0 + ArchUnit 规则 7）：<b>禁止 {@code Executors.newXxxThreadPool}</b>，
 * 一律显式构造 {@code ThreadPoolExecutor}。本包所有池都通过 Micrometer 的
 * {@code ExecutorServiceMetrics} 绑定到 {@code MeterRegistry}（AGENTS.md 借鉴地图 A 表
 * "必改：池名与 Micrometer 指标绑定"）。
 *
 * <p>Redis Stream 容器（{@code AbstractStreamProducer} / {@code AbstractStreamConsumer} 的
 * 装配）留到 P1a-05 首个向量化任务落地时再加，避免预留空配置。
 */
package io.annona.config.async;
