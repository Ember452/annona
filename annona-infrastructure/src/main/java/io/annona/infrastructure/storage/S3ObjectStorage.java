package io.annona.infrastructure.storage;

import io.annona.common.storage.ObjectStorage;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * {@link ObjectStorage} 的 AWS SDK v2 实现（借 🅖 FileStorageService 的调用形状）。
 * forcePathStyle=true：Silo/MinIO 是 path-style 端点（http://host/bucket/key），不用虚拟主机式。
 * delete 幂等（NoSuchKey 吞掉并 warn）：删除级联与孤儿补偿都依赖该语义。
 * 桶不存在时由部署方创建（compose 的 {@code MINIO_DEFAULT_BUCKETS}，见 s3-storage-silo-adr），
 * 本实现不做自动建桶——P0 已把该行为移交给 Silo 的启动 env。
 */
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client client;
    private final String bucket;

    public S3ObjectStorage(StorageProperties properties) {
        if (properties.getAccessKey().isBlank() || properties.getSecretKey().isBlank()) {
            throw new IllegalStateException("annona.storage.access-key/secret-key 未配置（endpoint 已设置时必填）");
        }
        this.bucket = properties.getBucket();
        this.client = S3Client.builder()
            .endpointOverride(URI.create(properties.getEndpoint()))
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
            .forcePathStyle(true)
            .build();
        ensureBucketExists();
    }

    /**
     * 启动时幂等建桶（借 🅖 FileStorageService.ensureBucketExists：404 → create、
     * 并发 409 容忍）。取代被 CI 证伪的 `MINIO_DEFAULT_BUCKETS` 自举——Silo fork 不执行
     * 该环境变量（head-bucket 404，s3-storage-silo-adr 修订 2），而“全新机器一条
     * `docker compose up` 拉起完整栈”（设计文档 §13）要求桶必须自动就位。
     */
    private void ensureBucketExists() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return;
        } catch (NoSuchBucketException e) {
            // fall through：建桶
        }
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("S3 桶不存在，已自动创建 bucket={}", bucket);
        } catch (BucketAlreadyOwnedByYouException e) {
            log.debug("S3 桶已被并发创建 bucket={}", bucket);
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        client.putObject(PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            log.warn("S3 对象已不存在（幂等删除） key={}", key);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /** 释放底层连接池（StorageConfig 的 destroyMethod="close" 依赖本方法——Spring 在
     * bean 定义期校验方法存在，包装类不透出 close 会直接拒绝启动，P1a-05 CI 实测）。 */
    public void close() {
        client.close();
    }
}
