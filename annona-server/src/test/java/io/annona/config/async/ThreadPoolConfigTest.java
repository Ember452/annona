package io.annona.config.async;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import io.annona.config.async.AnnonaThreadProperties.Pool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

/**
 * 端到端装配测试（P0-05）：Boot 上下文里 4 个线程池 Bean 存在且被 Micrometer 绑定，
 * 参数与 {@link AnnonaThreadProperties} 默认值一致。
 *
 * <p>test profile 排除 DataSource / Flyway / JPA autoconfig（见
 * {@code src/test/resources/application-test.yaml}），本测试不依赖 PG 与 Docker。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ThreadPoolConfig 装配 4 池 + Micrometer 绑定（P0-05）")
class ThreadPoolConfigTest {

    @Autowired
    @Qualifier("generalExecutor")
    private ThreadPoolTaskExecutor generalExecutor;

    @Autowired
    @Qualifier("aiIoExecutor")
    private ExecutorService aiIoExecutor;

    @Autowired
    @Qualifier("cpuExecutor")
    private ExecutorService cpuExecutor;

    @Autowired
    @Qualifier("queryExecutor")
    private ExecutorService queryExecutor;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AnnonaThreadProperties props;

    @Test
    @DisplayName("generalExecutor 是 ThreadPoolTaskExecutor，参数等于 properties 默认")
    void generalPoolMatchesProperties() {
        Pool expected = props.getGeneral();
        assertThat(generalExecutor.getCorePoolSize()).isEqualTo(expected.getCorePoolSize());
        assertThat(generalExecutor.getMaxPoolSize()).isEqualTo(expected.getMaxPoolSize());
        assertThat(generalExecutor.getThreadNamePrefix()).isEqualTo(expected.getThreadNamePrefix());
    }

    @Test
    @DisplayName("aiIoExecutor 是 ThreadPoolExecutor，AbortPolicy（防上游雪崩）")
    void aiIoPoolUsesAbortPolicy() {
        assertThat(aiIoExecutor).isInstanceOf(ThreadPoolExecutor.class);
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) aiIoExecutor;
        assertThat(tpe.getRejectedExecutionHandler())
            .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(tpe.getCorePoolSize()).isEqualTo(props.getAiIo().getCorePoolSize());
    }

    @Test
    @DisplayName("cpuExecutor 与 queryExecutor 用 CallerRunsPolicy（背压）")
    void cpuAndQueryPoolsUseCallerRunsPolicy() {
        assertThat(((ThreadPoolExecutor) cpuExecutor).getRejectedExecutionHandler())
            .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        assertThat(((ThreadPoolExecutor) queryExecutor).getRejectedExecutionHandler())
            .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("cpu 池 core 与 availableProcessors 相关（>=2）")
    void cpuPoolScaledToProcessors() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) cpuExecutor;
        assertThat(tpe.getCorePoolSize()).isEqualTo(Math.max(2, cores));
    }

    @Test
    @DisplayName("Micrometer 已注册 executor.* 指标，含 4 个 name tag（general/ai-io/cpu/query）")
    void micrometerBindsAllFourPools() {
        // ExecutorServiceMetrics 固定产出 base name 为 executor.* 的一组 Gauge/Counter，
        // 并把构造时传入的 metricName 写进 tag "name"（micrometer-core 1.x 约定）。
        java.util.Set<String> boundNames = meterRegistry.getMeters().stream()
            .filter(m -> m.getId().getName().startsWith("executor."))
            .map(m -> m.getId().getTag("name"))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(boundNames)
            .as("四个池都应绑定到 MeterRegistry")
            .contains("general", "ai-io", "cpu", "query");
    }
}
