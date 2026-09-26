/**
 * 可观测装配：trace_id 过滤器已落地，指标埋点按阶段逐个补。
 *
 * <p>已有：{@link TraceIdFilter} 负责把 {@code traceId} 写入 SLF4J MDC 并回写
 * {@code X-Trace-Id} 响应头；{@code logback-spring.xml} 用同一个键输出日志。
 * 这三者对齐后，“用户反馈里的 traceId → 服务端日志行”才能真跑通
 * （P0 曾出现过 filter 未实现但文档声称 traceId 非空的情况，见阶段总结 D17）。
 *
 * <p>待动（按阶段落，不提前造空类）：
 * <ul>
 *   <li>P1a-07：pgvector 检索延迟埋点（自定义 {@code Timer} + {@code @Timed}）</li>
 *   <li>P1b-10：token 用量 Counter（{@code annona.token.usage}，标签含 model / usage / provider）</li>
 *   <li>P3-06：语音端到端延迟预算表（P50/P95 Timer + Histogram）</li>
 * </ul>
 *
 * <p>Actuator 的 {@code MeterRegistry} 与 {@code /actuator/health} 由
 * {@code spring-boot-starter-actuator} 自动装配；线程池指标绑定在
 * {@link io.annona.config.async.ThreadPoolConfig} 内完成，不在本包重复。
 */
package io.annona.config.observability;
