/**
 * Web 层全局装配：MVC 定制、CORS、静态资源、SSE 超时、OpenAPI/Swagger 分组。
 *
 * <p>P0-09（B3）阶段本包<b>已落</b> {@link io.annona.config.web.OpenApiConfig}
 * 提供 {@code /v3/api-docs} 分组，前端 {@code pnpm gen:api} 直接消费。
 *
 * <p>剩余配置类到真实消费者出现时再落（AGENTS.md §3.2）：
 * <ul>
 *   <li>CORS：P0-10 或 P1a 首次跨域调试时（当前 annona-server 单 jar 同时
 *       提供前端与 API，无跨域需求）</li>
 *   <li>Jackson 定制：P1a-08 SSE 流式问答确定字段序列化策略时</li>
 *   <li>SSE 超时：P1a-08 首个 SSE 端点</li>
 *   <li>静态资源路径：Spring Boot 默认把 {@code classpath:/static/} 作为根
 *       服务，pnpm build 输出即命中，不需要 WebMvcConfigurer</li>
 * </ul>
 * 提前建空 Config 类是"预留灵活性"，违反 AGENTS.md §3.2。
 */
package io.annona.config.web;
