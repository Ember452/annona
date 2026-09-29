package io.annona.infrastructure.cache;

import io.annona.common.cache.SessionSnapshotPort;
import java.time.Duration;
import java.util.Optional;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 面试会话快照的 Redisson 实现：{@code interview:session:{id}} → 视图 JSON，24h TTL
 * （key 前缀与 TTL 口径借 🅖 InterviewSessionCache；annona 快照只存读视图不存题目列表——
 * 视图可由 DB 随时重建，缓存里放派生物是多余的一致性负担）。
 *
 * <p>全部方法吞 RuntimeException 降级（端口契约：缓存故障不传染请求）；与
 * {@link RedissonSessionStore} 不同——登录会话读不到必须报错，快照读不到只是慢一点。
 */
@Component
public class RedissonInterviewSnapshotCache implements SessionSnapshotPort {

    private static final Logger log = LoggerFactory.getLogger(RedissonInterviewSnapshotCache.class);

    private static final String KEY_PREFIX = "interview:session:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedissonClient client;

    public RedissonInterviewSnapshotCache(@Lazy RedissonClient client) {
        this.client = client;
    }

    @Override
    public void save(String sessionId, String viewJson) {
        try {
            bucket(sessionId).set(viewJson, TTL);
        } catch (RuntimeException e) {
            log.warn("面试快照写入失败（走 DB 读路径）：{}", e.getMessage());
        }
    }

    @Override
    public Optional<String> get(String sessionId) {
        try {
            return Optional.ofNullable(bucket(sessionId).get());
        } catch (RuntimeException e) {
            log.warn("面试快照读取失败（走 DB 读路径）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void evict(String sessionId) {
        try {
            bucket(sessionId).delete();
        } catch (RuntimeException e) {
            log.warn("面试快照失效失败（TTL 会兜底）：{}", e.getMessage());
        }
    }

    private RBucket<String> bucket(String sessionId) {
        return client.getBucket(KEY_PREFIX + sessionId, StringCodec.INSTANCE);
    }
}
