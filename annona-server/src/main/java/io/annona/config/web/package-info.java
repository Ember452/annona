/**
 * Web 层全局装配：MVC 定制、CORS、静态资源、SSE 超时、OpenAPI/Swagger 分组。
 *
 * <p>P0-05 阶段本包<b>仅有 package-info</b>；具体的 {@code CorsConfig} /
 * {@code JacksonConfig} / {@code OpenApiConfig} / {@code WebMvcAsyncConfiguration}
 * 等到各自真实消费者出现时再落：
 * <ul>
 *   <li>CORS：P0-09 annona-web 前端联调时（有真实跨域请求）</li>
 *   <li>Jackson 定制：P1a-08 SSE 流式问答确定字段序列化策略时</li>
 *   <li>OpenAPI：P0-09 类型生成脚本 {@code scripts/gen-api.ts} 落地时</li>
 *   <li>SSE 超时：P1a-08 首个 SSE 端点</li>
 * </ul>
 * 提前建空 Config 类是"预留灵活性"，违反 AGENTS.md §3.2。
 */
package io.annona.config.web;
