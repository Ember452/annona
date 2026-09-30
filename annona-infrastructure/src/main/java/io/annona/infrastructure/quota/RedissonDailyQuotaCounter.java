package io.annona.infrastructure.quota;

import io.annona.common.quota.DailyQuotaCounter;
import java.time.Duration;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Redisson 计数实现：{@code addAndGet} 后比较——计数与判断非单条 CAS，但配额是
 * 软闸门（略超几十 K token 无资损级后果），换来零 Lua 脚本与零维护面；
 * 每次调用都刷 TTL（窗口取"至当日结束"由调用方算好传入），杜绝"首写设 TTL 失败
 * 留下永不过期键"的孤儿形态。
 *
 * <p><b>fail-open</b>：Redis 运行时故障 → 放行 + warn。取舍：闸门失效最多损失当日
 * 成本控制，让面试/出题整体停摆才是事故；托管代持（真金白银）上线前此策略需重议
 * （llmprovider-metering-adr §何时重新评估）。
 */
@Component
public class RedissonDailyQuotaCounter implements DailyQuotaCounter {

    private static final Logger log = LoggerFactory.getLogger(RedissonDailyQuotaCounter.class);

    private final RedissonClient client;

    public RedissonDailyQuotaCounter(@Lazy RedissonClient client) {
        this.client = client;
    }

    @Override
    public boolean tryConsume(String key, long amount, long limit, Duration window) {
        if (limit <= 0) {
            return true;   // 不限档
        }
        try {
            RAtomicLong counter = client.getAtomicLong(key);
            long total = counter.addAndGet(amount);
            counter.expire(window);
            return total <= limit;
        } catch (RuntimeException e) {
            log.warn("配额计数失败（fail-open 放行）：{}", e.getMessage());
            return true;
        }
    }

    @Override
    public long current(String key) {
        try {
            return client.getAtomicLong(key).get();
        } catch (RuntimeException e) {
            log.warn("配额读取失败（按 0 处理）：{}", e.getMessage());
            return 0L;
        }
    }
}
