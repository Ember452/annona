/**
 * identity 业务模块：注册、登录、会话、失败锁定、scrypt 口令与透明重哈希、资料与数据请求。
 *
 * <p>职责：本地凭据（{@code local} 模式）写侧编排；活跃会话经 {@link io.annona.common.session.SessionStore}
 * 端口存 Redis（实现装配在 {@code infrastructure.cache}）。当前用户解析见 {@code config.web} 的会话过滤器。
 *
 * <p>{@code provider} 子包是 {@code io.annona.spi.identity.IdentityProvider} 的三种实现
 * （{@code local} / {@code platform} / {@code none}），按 {@code annona.identity.mode} 择一装配；
 * 放本模块而非 {@code annona-infrastructure}，因为它要访问本模块的仓储与建号服务，反过来会造成
 * modules→infra 的反向依赖（ArchUnit 规则 1）。
 *
 * <p>允许依赖：{@code io.annona.common.*}、{@code io.annona.spi.*}、Spring Data JPA、
 * spring-security-crypto、MapStruct。<b>禁止</b>依赖 {@code io.annona.infrastructure..}（ArchUnit 规则 1）
 * 或其他 {@code io.annona.modules.<other>..}。
 *
 * <p>分层：controller（路由/校验/委托）→ service（编排，{@code @Transactional} 只在此）→ repository（JPA）。
 * Entity 不外泄给前端，转换走 {@code mapper}（MapStruct）。
 */
package io.annona.modules.identity;
