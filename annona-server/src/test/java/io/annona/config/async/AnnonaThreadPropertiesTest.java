package io.annona.config.async;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.config.async.AnnonaThreadProperties.Pool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 纯逻辑单测：验证 {@link AnnonaThreadProperties} 的四类池默认值稳定，不被 yaml 意外覆盖时
 * 仍能给出合理起点（AGENTS.md §4 关键纯逻辑包必须有纯逻辑单测）。
 */
@DisplayName("AnnonaThreadProperties 默认参数（P0-05）")
class AnnonaThreadPropertiesTest {

    @Test
    @DisplayName("general 池默认 16/64/1000/60s + annona-async- 前缀")
    void generalDefaults() {
        Pool general = new AnnonaThreadProperties().getGeneral();
        assertThat(general.getCorePoolSize()).isEqualTo(16);
        assertThat(general.getMaxPoolSize()).isEqualTo(64);
        assertThat(general.getQueueCapacity()).isEqualTo(1000);
        assertThat(general.getKeepAliveSeconds()).isEqualTo(60L);
        assertThat(general.getThreadNamePrefix()).isEqualTo("annona-async-");
    }

    @Test
    @DisplayName("aiIo 池默认 8/32/200/60s + annona-ai-io- 前缀（比 general 小，见类 Javadoc）")
    void aiIoDefaults() {
        Pool aiIo = new AnnonaThreadProperties().getAiIo();
        assertThat(aiIo.getCorePoolSize()).isEqualTo(8);
        assertThat(aiIo.getMaxPoolSize()).isEqualTo(32);
        assertThat(aiIo.getQueueCapacity()).isEqualTo(200);
        assertThat(aiIo.getThreadNamePrefix()).isEqualTo("annona-ai-io-");
    }

    @Test
    @DisplayName("cpu 池 core/max 按 availableProcessors 定尺，且 core>=2 max>=4 max>=core")
    void cpuDefaultsScaleWithProcessors() {
        Pool cpu = new AnnonaThreadProperties().getCpu();
        int cores = Runtime.getRuntime().availableProcessors();
        assertThat(cpu.getCorePoolSize()).isEqualTo(Math.max(2, cores));
        assertThat(cpu.getMaxPoolSize()).isEqualTo(Math.max(4, cores * 2));
        assertThat(cpu.getMaxPoolSize()).isGreaterThanOrEqualTo(cpu.getCorePoolSize());
        assertThat(cpu.getThreadNamePrefix()).isEqualTo("annona-cpu-");
    }

    @Test
    @DisplayName("query 池默认 8/24/400/120s + annona-query- 前缀")
    void queryDefaults() {
        Pool query = new AnnonaThreadProperties().getQuery();
        assertThat(query.getCorePoolSize()).isEqualTo(8);
        assertThat(query.getMaxPoolSize()).isEqualTo(24);
        assertThat(query.getQueueCapacity()).isEqualTo(400);
        assertThat(query.getKeepAliveSeconds()).isEqualTo(120L);
        assertThat(query.getThreadNamePrefix()).isEqualTo("annona-query-");
    }

    @Test
    @DisplayName("Pool 有 no-arg 构造 + setter，能被 Spring @ConfigurationProperties mutable binding")
    void poolSupportsMutableBinding() {
        Pool pool = new Pool();
        pool.setCorePoolSize(4);
        pool.setMaxPoolSize(8);
        pool.setQueueCapacity(64);
        pool.setKeepAliveSeconds(30L);
        pool.setThreadNamePrefix("custom-");
        assertThat(pool.getCorePoolSize()).isEqualTo(4);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("custom-");
    }
}
