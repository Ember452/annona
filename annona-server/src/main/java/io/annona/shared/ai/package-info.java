/**
 * 跨模块复用的 AI 调用工具（结构化输出统一重试器等）。
 *
 * <p>本包存在的原因：{@link io.annona.shared.ai.StructuredOutputInvoker} 要调
 * {@code spi.model.ModelProvider} 做"生成→解析→带错误反馈重试"，而依赖方向是
 * {@code modules → spi → common}——{@code common} 看不见 {@code spi}，故承载
 * spi 调用的通用件只能落 {@code shared}（shared 允许依赖 spi + common）。
 * 出题管道（P1b-02）与评估（P1b-06）共用同一重试器，避免两处各写一套
 * （skill-questionbank-adr §决策 6 与其后续修订）。
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}、JDK、Spring、Jackson。
 * 禁止：依赖 {@code io.annona.modules.*} 或 {@code io.annona.infrastructure.*}。
 *
 * <p>当前内容：{@code StructuredOutputInvoker} / {@code StructuredOutputProperties}（P1b-02）。
 */
package io.annona.shared.ai;
