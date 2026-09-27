package io.annona.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储连接配置（{@code annona.storage.*}）。env 映射写在 application.yaml
 * （S3_ENDPOINT 等，与 Redis 同款不同源映射规则）。endpoint 为空 = 不装配
 * S3ObjectStorage bean（上传时报 KB_DOC_STORAGE_NOT_CONFIGURED，启动不拦）。
 */
@ConfigurationProperties(prefix = "annona.storage")
public class StorageProperties {

    /** S3 兼容端点（Silo：http://storage:9000）；空串 = 本机未启用对象存储。 */
    private String endpoint = "";

    private String bucket = "annona";

    private String accessKey = "";

    private String secretKey = "";

    /** Silo/MinIO 语义上无真实区域，占位 us-east-1（AWS SDK 必填）。 */
    private String region = "us-east-1";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
