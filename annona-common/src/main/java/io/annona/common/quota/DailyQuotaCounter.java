package io.annona.common.quota;

import java.time.Duration;

/**
 * 计数型配额端口（llmprovider-metering-adr §熔断）：原子"若未超限则累加"，超限判定
 * 在调用外置——收敛为单点组件而非散落注解，因为 annona 尚无 {@code @RateLimit} 骨架
 * （批 2 计划 M4：瞬时限流与每日配额是两个概念，注解移植另立任务）。
 *
 * <p>fail-open 语义在实现侧决定并写进其注释（Redis 故障时放行是运维闸门失效，
 * 关停核心功能才是事故）；端口只约定返回值：true=未超限且已计数，false=超限。
 */
public interface DailyQuotaCounter {

    /**
     * @param key    计数键（调用方给全，含日期粒度）
     * @param amount 本次消耗量
     * @param limit  上限；{@code <=0} 表示不限
     */
    boolean tryConsume(String key, long amount, long limit, Duration window);

    /** 当前累计值（纯读，不计数）；键不存在或实现故障时返回 0（fail-open 同口径）。 */
    long current(String key);
}
