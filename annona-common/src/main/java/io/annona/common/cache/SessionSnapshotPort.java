package io.annona.common.cache;

import java.util.Optional;

/**
 * 会话快照存储端口（interview-session-adr §冷热分层）。端口在 common、实现在
 * infrastructure.cache——{@code SessionStore} 同型的依赖倒置（AGENTS §4：无外部实现方
 * 需求 → 内部解耦端口放 common，业务模块编译期拿不到 Redisson）。
 *
 * <p>契约：Redis 热缓存只存<b>读视图 JSON</b>，DB 永远是真值；实现必须容忍 Redis 不可用
 * （读 miss 返回 empty、写失败静默），不得把缓存故障传染给请求——与 {@code SessionStore}
 * 的"透传运行时异常"不同，登录会话读不到必须报错，快照读不到只是慢一点。
 */
public interface SessionSnapshotPort {

    /** 写快照（实现侧带 TTL）；失败静默。 */
    void save(String sessionId, String viewJson);

    /** 读快照；miss 或 Redis 不可用返回 {@link Optional#empty()}。 */
    Optional<String> get(String sessionId);

    /** 失效快照（任何写路径 DB 提交后调用）；幂等，失败静默。 */
    void evict(String sessionId);
}
