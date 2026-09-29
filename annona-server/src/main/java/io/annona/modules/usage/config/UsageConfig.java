package io.annona.modules.usage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 装配计量配置（RedissonConfig 同型先例）。 */
@Configuration
@EnableConfigurationProperties(UsageProperties.class)
class UsageConfig {
}
