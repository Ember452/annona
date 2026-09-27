package io.annona.infrastructure.parse;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 解析线程池装配。显式构造 ThreadPoolExecutor（全仓禁 {@code Executors.newXxx}，
 * ArchUnit 机检），守护线程 + 命名 {@code annona-parse-N}，队列 20 + Abort——
 * 队列满即拒绝（解析是入库瓶颈时宁可让上游报错重试，不无限堆积内存）。
 */
@Configuration
@EnableConfigurationProperties(DocumentParseProperties.class)
public class ParseConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService documentParseExecutor(DocumentParseProperties properties) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "annona-parse-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(properties.getPoolSize(), properties.getPoolSize(),
            0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(20), factory, new ThreadPoolExecutor.AbortPolicy());
    }
}
