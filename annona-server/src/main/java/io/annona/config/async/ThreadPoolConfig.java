package io.annona.config.async;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.annona.config.async.AnnonaThreadProperties.Pool;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 四类线程池装配（P0-05）。启动时每个池各打印一行 INFO 日志——这是 P0-05 验收
 * "启动日志打印四类池参数"的直接落点。
 *
 * <p>三件事被显式绑定到本类：
 * <ol>
 *   <li>禁止 {@code Executors.newXxx}：一律 {@code new ThreadPoolExecutor(...)}（AGENTS.md §0
 *       与 ArchUnit 规则 7）。</li>
 *   <li>Micrometer 绑定：{@code ExecutorServiceMetrics.monitor} 把池指标推入
 *       {@link MeterRegistry}；使用 Micrometer 默认命名约定（base name =
 *       {@code executor.*}、tag {@code name=general/ai-io/cpu/query}），与 Boot 自身
 *       {@code DataSourceHealthIndicator} 等指标风格一致（AGENTS.md 借鉴地图 A 表 P0-01/P1 行
 *       "必改：池名与 Micrometer 指标绑定"）。</li>
 *   <li>拒绝策略差异化：AI-IO 池 {@code AbortPolicy}（上游 LLM 慢或挂时快速失败，
 *       避免调用方线程被拖垮引发雪崩）；其余 3 池 {@code CallerRunsPolicy}（背压）。
 *       <b>注</b>：“调用方线程被拉去跑任务”对 {@code cpuExecutor} 在高分块并发时可能
 *       反过来拖住请求线程；因为没有真实提交方，这个取舍现在无法用数据定，
 *       已记入阶段总结技术债 D21（触发条件：P1a-04 心跳/ETL 有真实提交方并压测）。</li>
 *   <li>关闭：三个原生池的 {@code destroyMethod} 置空，统一由
 *       {@link AnnonaExecutorShutdown} 做 shutdown + awaitTermination，
 *       否则在飞任务会被静默丢弃。</li>
 * </ol>
 *
 * <p>本类<b>不</b>搬 MockPilot 的 {@code Threads.printException} 工具类——它内部有
 * {@code Executors.newXxx} 变体（AGENTS.md §0 禁令），改为在 {@code afterExecute} 匿名重写
 * 内直接 SLF4J 打日志。
 */
@Configuration
@EnableConfigurationProperties(AnnonaThreadProperties.class)
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    private final AnnonaThreadProperties props;
    private final MeterRegistry meterRegistry;

    public ThreadPoolConfig(AnnonaThreadProperties props, MeterRegistry meterRegistry) {
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 通用异步池；Spring {@code @Async} 默认走这里。用 {@link ThreadPoolTaskExecutor}
     * 是为了享受 Spring 的生命周期管理（graceful shutdown、TaskDecorator 钩子等）。
     */
    @Bean(name = "generalExecutor")
    public ThreadPoolTaskExecutor generalExecutor() {
        Pool pool = props.getGeneral();
        logPool("general", pool, "CallerRunsPolicy");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCorePoolSize());
        executor.setMaxPoolSize(pool.getMaxPoolSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setKeepAliveSeconds((int) pool.getKeepAliveSeconds());
        executor.setThreadNamePrefix(pool.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        new ExecutorServiceMetrics(executor.getThreadPoolExecutor(), "general", Tags.empty())
            .bindTo(meterRegistry);
        return executor;
    }

    /** AI-IO 池：LLM HTTP 调用。AbortPolicy 快速拒绝上游慢的场景。 */
    @Bean(name = "aiIoExecutor", destroyMethod = "")
    public ExecutorService aiIoExecutor() {
        Pool pool = props.getAiIo();
        logPool("ai-io", pool, "AbortPolicy");
        return buildPool("ai-io", pool, new ThreadPoolExecutor.AbortPolicy());
    }

    /** CPU 池：分块 / 加密 / 结构化输出解析等纯计算。 */
    @Bean(name = "cpuExecutor", destroyMethod = "")
    public ExecutorService cpuExecutor() {
        Pool pool = props.getCpu();
        logPool("cpu", pool, "CallerRunsPolicy");
        return buildPool("cpu", pool, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 查询池：只读聚合（热力图、趋势、决策面板数据）。 */
    @Bean(name = "queryExecutor", destroyMethod = "")
    public ExecutorService queryExecutor() {
        Pool pool = props.getQuery();
        logPool("query", pool, "CallerRunsPolicy");
        return buildPool("query", pool, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private ExecutorService buildPool(String name, Pool pool, RejectedExecutionHandler rejected) {
        ThreadFactory threadFactory = namedThreadFactory(pool.getThreadNamePrefix());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            pool.getCorePoolSize(),
            pool.getMaxPoolSize(),
            pool.getKeepAliveSeconds(),
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(pool.getQueueCapacity()),
            threadFactory,
            rejected) {
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                super.afterExecute(r, t);
                if (t != null) {
                    log.error("异步任务未捕获异常 pool={}", name, t);
                }
            }
        };
        // 把池指标绑到 MeterRegistry，但返回 raw executor；
        // 若用 ExecutorServiceMetrics.monitor 会把实例包装成 TimedExecutorService，
        // 丢失 ThreadPoolExecutor 的具体类型（无法直接看拒绝策略与 core/max）。
        new ExecutorServiceMetrics(executor, name, Tags.empty()).bindTo(meterRegistry);
        return executor;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private static void logPool(String name, Pool pool, String policy) {
        log.info("Thread pool [{}] core={} max={} queue={} keepAlive={}s prefix={} reject={}",
            name, pool.getCorePoolSize(), pool.getMaxPoolSize(), pool.getQueueCapacity(),
            pool.getKeepAliveSeconds(), pool.getThreadNamePrefix(), policy);
    }
}
