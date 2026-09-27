package io.annona.infrastructure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配。由 {@code annona.storage.enabled} 显式开关（默认关）——不能改用
 * 「endpoint 键存在与否」判断：application.yaml 的 {@code ${S3_ENDPOINT:}} 把键<b>恒定
 * 定义</b>为空串，而 @ConditionalOnProperty 只判键存在、空串也命中，会把未配置环境
 * （docker IT）强进 S3ObjectStorage 构造器再抛「Key 未配置」，整个上下文连坐
 * （P1a-05 CI 实测，26 个 IT 全跳过）。enabled=true 时 Key 缺失仍是配置错误、启动即抛。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "annona.storage", name = "enabled", havingValue = "true")
    public S3ObjectStorage s3ObjectStorage(StorageProperties properties) {
        return new S3ObjectStorage(properties);
    }
}
