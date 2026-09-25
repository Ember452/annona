/**
 * 启动期一次性动作。
 *
 * <p>本包的类<b>只在整个应用生命周期里执行一次</b>，且必须在业务 Bean 装配完成之前
 * 或之后立刻收工，不留常驻状态。当前内容：
 * <ul>
 *   <li>{@link io.annona.bootstrap.StartupValidator}——P0-08；prod profile 缺 KEK 时
 *       在 {@code ApplicationEnvironmentPreparedEvent} 上直接抛错，早于任何 Bean。</li>
 *   <li>DataSeeder——P5-05 才建，负责 {@code annona demo seed --weeks 6}。</li>
 * </ul>
 *
 * <p>依赖方向：本包可以引用 {@code io.annona.common}、Spring Core/Boot 事件 API；
 * 禁止引用 {@code io.annona.modules..}（启动期校验早于业务上下文，反向依赖会形成
 * 循环）。PG 扩展检查放在 {@code config/persistence.FlywayExtensionGuard} 而不是本包，
 * 因为它需要 {@code DataSource} 与 Flyway 的时序耦合，语义更贴近持久层装配。
 */
package io.annona.bootstrap;
