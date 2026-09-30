package io.annona.infrastructure.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 装配 KEK 配置（StorageConfig 同型先例）。 */
@Configuration
@EnableConfigurationProperties(KekProperties.class)
class CryptoConfig {
}
