/**
 * annona-server 的全局装配包：所有 {@code @Configuration} Bean 的家。
 *
 * <p>子包与职责（与 docs/annona-项目结构.md §4 一致）：
 * <ul>
 *   <li>{@code web/}         MVC 定制、CORS、静态资源、SSE 超时、OpenAPI/Swagger 分组</li>
 *   <li>{@code async/}       四类线程池（通用 / AI-IO / CPU / 查询）+ Redis Stream 容器注册</li>
 *   <li>{@code persistence/} JPA / 事务 / 审计字段 / pgvector 类型注册 / Flyway 迁移策略</li>
 *   <li>{@code security/}    过滤器链、密码编码器、Key 解密上下文、CSP</li>
 *   <li>{@code properties/}  {@code @ConfigurationProperties} 类（每域一个，禁止散落 {@code @Value}）</li>
 *   <li>{@code observability/} Micrometer 指标、健康指示器、trace_id 过滤器</li>
 * </ul>
 *
 * <p>P0-05 阶段只有 {@code async/} 与 {@code persistence/} 有实际 Bean；
 * 其他 4 包保持骨架，等对应能力被真实业务需要时再落类，避免"预留可配置项"。
 *
 * <p>依赖方向：{@code io.annona.config..} 可以引用 {@code io.annona.common}、{@code io.annona.spi}
 * 与 Spring / Micrometer；禁止引用 {@code io.annona.modules..} 内部类（配置层反过来依赖业务，
 * 会破坏"业务不感知装配"的边界）。
 */
package io.annona.config;
