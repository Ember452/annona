package io.annona.infrastructure.cache;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

/**
 * 构造单节点 {@link RedissonClient}（dev/docker 单机；identity ADR §后续修订 §1）。
 *
 * <p>Redisson 是必选依赖而非性能优化项：活跃会话无处可存就没有登录态（ADR §后果 §1）。
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    RedissonClient redissonClient(RedisProperties props) {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + props.getHost() + ":" + props.getPort())
            .setDatabase(props.getDatabase());
        if (StringUtils.hasText(props.getPassword())) {
            config.useSingleServer().setPassword(props.getPassword());
        }
        return Redisson.create(config);
    }
}
