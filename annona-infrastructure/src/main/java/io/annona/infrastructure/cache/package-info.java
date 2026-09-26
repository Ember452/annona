/**
 * Redis / Redisson 适配（会话、限流、Stream、在线状态）。
 *
 * <p>职责：持有 {@code RedissonClient} Bean 并实现 {@code annona-common} 声明的基础设施端口
 * （如 {@link io.annona.common.session.SessionStore} 的 Redisson 实现）。
 *
 * <p>允许依赖：{@code annona-common}、{@code annona-spi}、Redisson 与 Spring。
 * 禁止：包含业务规则（属 {@code io.annona.modules.*}）。
 *
 * <p>注册方式：本包被 annona-server 的组件扫描（{@code io.annona} 根包）在<b>运行期</b>拾取；
 * annona-server 对 infra 是 runtime 依赖，编译期只看得见 common 端口。
 */
package io.annona.infrastructure.cache;
