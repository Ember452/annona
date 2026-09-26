package io.annona.common.study;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 学习会话心跳时间线端口（study-collection-adr §决策 §1）。
 *
 * <p>心跳是「活着的证明」而非数据同步：前端 15s 一次写入 Redis ZSET（TTL 2h 自清），
 * finish 时服务端读全量时间线判质量——心跳不入 study_event、不落 DB 列（ADR §否决的备选）。
 * 本接口是<b>依赖倒置的端口</b>：定义在 {@code annona-common}（纯 JDK 签名，零框架依赖），
 * 实现（Redisson ZSET）在 {@code annona-infrastructure.cache}（与 {@code SessionStore}
 * 同款装配），{@code modules/study} 只注入本接口——满足 ArchUnit「modules 不 import
 * infrastructure」，且 annona-server 编译期看不到 Redisson。
 *
 * <p>约定：所有方法<b>不抛业务异常</b>；Redis 不可用时实现只透传底层运行时异常，
 * 由调用方决定降级。{@code sessionId} 由调用方保证非 null。
 */
public interface HeartbeatTimeline {

    /** 记一次心跳（幂等，可任意频率调用；同毫秒重复心跳天然去重）。 */
    void touch(UUID sessionId, Instant at);

    /** 全量心跳时刻（按时间升序）；key 不存在时返回空列表。 */
    List<Instant> timeline(UUID sessionId);

    /** finish 后清理；对不存在的 key 幂等。 */
    void evict(UUID sessionId);
}
