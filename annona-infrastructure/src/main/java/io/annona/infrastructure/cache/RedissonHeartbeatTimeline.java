package io.annona.infrastructure.cache;

import io.annona.common.study.HeartbeatTimeline;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@link HeartbeatTimeline} 的 Redisson 实现：{@code study:hb:{sessionId}} ZSET，
 * member = score = 心跳 epoch 毫秒（同毫秒重复心跳天然去重），TTL 2h 自清——
 * 会话正常 finish 后由调用方显式 evict，异常终止（页面关闭）靠 TTL 兜底，不写定时任务。
 */
@Component
public class RedissonHeartbeatTimeline implements HeartbeatTimeline {

    private static final String KEY_PREFIX = "study:hb:";
    private static final Duration TTL = Duration.ofHours(2);

    private final RedissonClient client;

    public RedissonHeartbeatTimeline(@Lazy RedissonClient client) {
        this.client = client;
    }

    @Override
    public void touch(UUID sessionId, Instant at) {
        RScoredSortedSet<Long> set = set(sessionId);
        set.add(at.toEpochMilli(), at.toEpochMilli());
        set.expire(TTL);
    }

    @Override
    public List<Instant> timeline(UUID sessionId) {
        // 会话最长 240min、心跳点 ≤ 数千，一次 ZRANGE 全取可承受；readAll 按 score 升序返回
        return set(sessionId).readAll().stream()
            .map(Instant::ofEpochMilli)
            .sorted()
            .toList();
    }

    @Override
    public void evict(UUID sessionId) {
        set(sessionId).delete();
    }

    private RScoredSortedSet<Long> set(UUID sessionId) {
        return client.getScoredSortedSet(KEY_PREFIX + sessionId, LongCodec.INSTANCE);
    }
}
