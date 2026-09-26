package io.annona.infrastructure.cache;

import io.annona.common.session.SessionStore;
import java.time.Duration;
import java.util.Optional;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@link SessionStore} 的 Redisson 实现：{@code session:{token}} → {@code userId}，
 * 值为纯字符串（{@link StringCodec}），TTL 由 create/touch 设置（identity ADR §决策 §3）。
 *
 * <p>借鉴 🅖 {@code InterviewSessionCache} 的「key 前缀 + Duration TTL + expire 续期」形状，
 * 但改用 Redisson {@link RBucket} 而非其自研 {@code RedisService}，且会话值只存 userId
 * （annona 会话无需缓存问题列表——那是面试会话，属 P1b）。
 */
@Component
public class RedissonSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "session:";

    private final RedissonClient client;

    public RedissonSessionStore(@Lazy RedissonClient client) {
        this.client = client;
    }

    @Override
    public void create(String token, String userId, Duration ttl) {
        bucket(token).set(userId, ttl);
    }

    @Override
    public Optional<String> readUserId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bucket(token).get());
    }

    @Override
    public void touch(String token, Duration ttl) {
        bucket(token).expire(ttl);
    }

    @Override
    public void delete(String token) {
        bucket(token).delete();
    }

    private RBucket<String> bucket(String token) {
        return client.getBucket(KEY_PREFIX + token, StringCodec.INSTANCE);
    }
}
