/**
 * 可观测装配：Micrometer 指标、健康指示器、trace_id 过滤器。
 *
 * <p>P0-05 阶段本包<b>仅有 package-info</b>。P0-03 的 {@code Result<T>} 已经预留
 * {@code traceId} 字段（从 SLF4J MDC 读取），但注入 MDC 的 filter 与自定义
 * {@code HealthIndicator} 到真实需要时再落：
 * <ul>
 *   <li>P0-12（B5）：CI 上线时增加 {@code TraceIdFilter}（servlet filter，写入 MDC）
 *       与 logback-spring.xml 的 {@code %X{traceId}} 转换符</li>
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
