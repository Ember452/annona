package io.annona.config.async;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 四类线程池的可调参数（{@code annona.thread-pool.*}）。
 *
 * <p>默认值即"当前假设下的合理起点"：
 * <ul>
 *   <li>general：16/64/1000/60s，与 Spring {@code @Async} 常见负载匹配</li>
 *   <li>aiIo：8/32/200/60s，比通用小是因为 LLM HTTP 调用慢且失败率非零，
 *       上游打满时 {@code AbortPolicy} 快速拒绝比堆积更负责</li>
 *   <li>cpu：按 {@link Runtime#availableProcessors()} 计算，分块与加密等纯计算
 *       开多了只切上下文不涨吞吐</li>
 *   <li>query：8/24/400/120s，热力图 / 趋势类只读聚合的常态</li>
 * </ul>
 *
 * <p>{@code threadNamePrefix} 也是字段默认——yaml 只在需要覆盖时写；不做"预留可配置项"。
 *
 * <p>setter 是 Spring {@code @ConfigurationProperties} mutable binding 所需（不用 Lombok
 * 避免引入依赖）；对 record 表达的偏好让位于 Boot 4 的 nested-class binding 稳定性。
 */
@ConfigurationProperties(prefix = "annona.thread-pool")
public class AnnonaThreadProperties {

    private Pool general = new Pool(16, 64, 1000, 60L, "annona-async-");
    private Pool aiIo = new Pool(8, 32, 200, 60L, "annona-ai-io-");
    private Pool cpu = new Pool(defaultCpuCore(), defaultCpuMax(), 256, 60L, "annona-cpu-");
    private Pool query = new Pool(8, 24, 400, 120L, "annona-query-");

    private static int defaultCpuCore() {
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    private static int defaultCpuMax() {
        return Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    }

    public Pool getGeneral() {
        return general;
    }

    public void setGeneral(Pool general) {
        this.general = general;
    }

    public Pool getAiIo() {
        return aiIo;
    }

    public void setAiIo(Pool aiIo) {
        this.aiIo = aiIo;
    }

    public Pool getCpu() {
        return cpu;
    }

    public void setCpu(Pool cpu) {
        this.cpu = cpu;
    }

    public Pool getQuery() {
        return query;
    }

    public void setQuery(Pool query) {
        this.query = query;
    }

    /** 单个线程池的参数集；字段命名与 {@link java.util.concurrent.ThreadPoolExecutor} 构造函数一致。 */
    public static class Pool {

        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
        private long keepAliveSeconds;
        private String threadNamePrefix;

        public Pool() {
        }

        public Pool(int corePoolSize, int maxPoolSize, int queueCapacity,
                    long keepAliveSeconds, String threadNamePrefix) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queueCapacity = queueCapacity;
            this.keepAliveSeconds = keepAliveSeconds;
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(long keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
