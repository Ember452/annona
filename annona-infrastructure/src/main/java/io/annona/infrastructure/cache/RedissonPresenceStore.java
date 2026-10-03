package io.annona.infrastructure.cache;

import io.annona.common.presence.PresencePort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@link PresencePort} 的 Redisson 实现：全局单键 {@code presence:global} ZSET，
 * member = userId（匿名计数不外泄身份，仅服务端可见）、score = 最后在场 epoch 毫秒。
 *
 * <p>与 {@link RedissonHeartbeatTimeline} 同族但两处不同：① 键级 TTL 只作崩溃兜底
 * （10min，每次 touch 刷新）——正常清理靠读时 ZREMRANGEBYSCORE 剪枝，因为多用户共用
 * 一键，无法像心跳时间线那样“结束即 evict”；② member 用 StringCodec 存 userId 字符串。
 */
@Component
public class RedissonPresenceStore implements PresencePort {

    private static final String KEY = "presence:global";
    /** 键级 TTL：纯兜底（全员异常消失时整键过期），正常剪枝走读时按窗口清理。 */
    private static final Duration KEY_TTL = Duration.ofMinutes(10);

    private final RedissonClient client;

    public RedissonPresenceStore(@Lazy RedissonClient client) {
        this.client = client;
    }

    @Override
    public void touch(UUID userId, Instant at) {
        RScoredSortedSet<String> set = set();
        set.add(at.toEpochMilli(), userId.toString());
        set.expire(KEY_TTL);
    }

    @Override
    public long activeCount(Instant at, Duration window) {
        RScoredSortedSet<String> set = set();
        double floor = at.minus(window).toEpochMilli();
        // 滑窗剪枝：窗口外成员直接清（键级 TTL 只是兜底，常态清理在这里）
        set.removeRangeByScore(0, true, floor, false);
        return set.count(floor, false, Double.POSITIVE_INFINITY, true);
    }

    private RScoredSortedSet<String> set() {
        return client.getScoredSortedSet(KEY, StringCodec.INSTANCE);
    }
}
