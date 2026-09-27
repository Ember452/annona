package io.annona.infrastructure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配。endpoint 未配置时<b>不装配</b> bean（知识上传服务注入
 * Optional 判空报 KB_DOC_STORAGE_NOT_CONFIGURED）——none/无存储环境启动不拦，
 * 错误后移到使用点（knowledge-ingestion-adr §决策 9）。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "annona.storage", name = "endpoint")
    public S3ObjectStorage s3ObjectStorage(StorageProperties properties) {
        return new S3ObjectStorage(properties);
    }
}
