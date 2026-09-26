package io.annona.common.session;

import java.time.Duration;
import java.util.Optional;

/**
 * 活跃会话存储端口（identity-credential-storage-adr §决策 §3）。
 *
 * <p>活跃令牌只存 Redis（7 天滑动 TTL），PG 的 {@code user_session} 仅异步写审计投影。
 * 本接口是<b>依赖倒置的端口</b>：定义在 {@code annona-common}（纯 JDK 签名，零框架依赖），
 * 实现（Redisson）在 {@code annona-infrastructure.cache}，业务模块 {@code modules/identity}
 * 只注入本接口——这样满足 ArchUnit「modules 不 import infrastructure」，且 annona-server
 * 编译期看不到 Redisson。详见该 ADR「后续修订」§2。
 *
 * <p>约定：所有方法<b>不抛业务异常</b>；Redis 不可用时由上层决定降级，实现类只透传底层运行时异常。
 * {@code token} 由调用方保证非 null 非空白；唯一例外是 {@link #readUserId} 对 null 安全（返回 empty）。
 */
public interface SessionStore {

    /** 签发会话：写入 {@code token -> userId} 并设置 TTL。 */
    void create(String token, String userId, Duration ttl);

    /** 读取令牌对应的主键（纯读，不改 TTL）；令牌不存在时返回 {@link Optional#empty()}。 */
    Optional<String> readUserId(String token);

    /** 滑动续期：把令牌 TTL 重置为 {@code ttl}（7 天滑动语义由 SessionService 在读命中后调用）。 */
    void touch(String token, Duration ttl);

    /** 失效会话（登出、改密后旧口令失效、超额熔断踢下线都走这里）。幂等。 */
    void delete(String token);
}
