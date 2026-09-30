/**
 * usage 计量模块（P1b-10）：模型用量账（token_usage）、每日配额熔断与成本查询端点。
 *
 * <p>归属裁决（llmprovider-metering-adr）：记账装饰器在本模块（要写本模块的表），
 * 上下文与配额端口在 common（模型出口与业务模块都要看见，SessionStore 同型），
 * 原始 provider 在 infra（SDK 边界）。装饰器 @Primary 吃掉 ModelProvider 注入点，
 * 业务调用零感知——新场景接入只需在执行线程内 {@code UsageContext.bind}。
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}、{@code io.annona.shared.*}。
 */
package io.annona.modules.usage;
