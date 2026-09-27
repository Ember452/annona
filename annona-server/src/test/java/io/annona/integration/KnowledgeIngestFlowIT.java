package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.storage.ObjectStorage;
import io.annona.modules.knowledge.dto.UploadResponse;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.ingest.KnowledgeUploadService;
import io.annona.modules.knowledge.ops.KnowledgeDocLifecycleService;
import io.annona.modules.knowledge.repository.KbDocChunkRepository;
import io.annona.modules.knowledge.repository.KbDocRepository;
import io.annona.spi.fake.FakeEmbeddingProvider;
import io.annona.spi.model.EmbeddingProvider;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 知识入库全链路端到端（批 2 闸门，审计 P0-1 的唯一验收凭证）：真实 PG + Redis 上
 * 走完 上传 → Redis Stream 消费 → 解析 → 分块落库 → 条件状态机推进 → READY 全程。
 *
 * <p>切片测试把仓储 mock 掉，探不到 @Modifying 缺事务这类只在真库暴露的问题（P1a-05
 * 实测教训：本机 198 个测试全绿、真库管线一启动就炸）。S3 用内存 Fake、embedding 用
 * 确定性 Fake（1024 维对齐 DDL）——被替换的是外部协议端点，不是被测的管线逻辑。
 * 切片全绿给不出这个保证，本 IT 绿是批 2（检索）开工的前置条件。
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("知识入库全链路：上传→READY（真实 PG+Redis，批 2 闸门）")
class KnowledgeIngestFlowIT {

    @Autowired
    private KnowledgeUploadService uploadService;
    @Autowired
    private KnowledgeDocLifecycleService lifecycleService;
    @Autowired
    private KbDocRepository docRepository;
    @Autowired
    private KbDocChunkRepository chunkRepository;
    @Autowired
    private JdbcTemplate jdbc;

    /** 内存对象存储：替换 S3 协议端点（本 IT 无 storage 容器），管线语义不受影响。 */
    static final class InMemoryStorage implements ObjectStorage {
        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public synchronized void put(String key, byte[] content, String contentType) {
            objects.put(key, content.clone());
        }

        @Override
        public synchronized byte[] get(String key) {
            byte[] content = objects.get(key);
            if (content == null) {
                throw new IllegalArgumentException("object not found: " + key);
            }
            return content.clone();
        }

        @Override
        public synchronized void delete(String key) {
            objects.remove(key);
        }

        @Override
        public synchronized boolean exists(String key) {
            return objects.containsKey(key);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        ObjectStorage objectStorage() {
            return new InMemoryStorage();
        }

        @Bean
        EmbeddingProvider embeddingProvider() {
            return new FakeEmbeddingProvider(); // 1024 维，与 V4 向量列对齐
        }
    }

    @Test
    @DisplayName("上传 md → 异步管线推进 READY → 分块落库 → 重复上传幂等 → 删除级联")
    void uploadReachesReadyThenDuplicateIsFree() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID directionId = insertBuiltinDirection();
        byte[] content = ("# 章节一\n\n这是用于端到端验证的正文内容，长度足以构成至少一个分块。\n\n"
            + "## 小节\n\n第二段正文，验证标题路径进入分块。").getBytes();

        UploadResponse first = uploadService.upload(userId.toString(), content,
            "讲义.md", directionId.toString());
        assertThat(first.duplicate()).isFalse();

        KbDocEntity doc = awaitTerminal(first.id(), Duration.ofSeconds(30));
        assertThat(doc.getStatus()).as("管线应推进到 READY（失败原因: %s）", doc.getError())
            .isEqualTo(KbDocEntity.STATUS_READY);
        assertThat(chunkRepository.findByDocIdOrderByChunkIndexAsc(doc.getId())).isNotEmpty();

        // hash 幂等：同用户同内容重复上传零消耗，直接复用已有文档
        UploadResponse second = uploadService.upload(userId.toString(), content,
            "讲义.md", directionId.toString());
        assertThat(second.duplicate()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());

        // 删除级联：行与分块同事务清理（S3 afterCommit 走内存 Fake）
        lifecycleService.delete(userId.toString(), first.id().toString());
        assertThat(docRepository.findById(first.id())).isEmpty();
        assertThat(chunkRepository.findByDocIdOrderByChunkIndexAsc(first.id())).isEmpty();
    }

    private UUID insertBuiltinDirection() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO direction (id, key, name, origin) VALUES (?, ?, ?, 'SKILL_BUILTIN')",
            id, "e2e-ingest-" + UUID.randomUUID().toString().substring(0, 8), "E2E 入库方向");
        return id;
    }

    /** 轮询等待异步管线到终态；FAILED 立即失败并带出 error，超时抛断言。 */
    private KbDocEntity awaitTerminal(UUID docId, Duration deadline) throws InterruptedException {
        AtomicReference<KbDocEntity> latest = new AtomicReference<>();
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            Optional<KbDocEntity> doc = docRepository.findById(docId);
            doc.ifPresent(latest::set);
            if (doc.isPresent()) {
                String status = doc.get().getStatus();
                if (KbDocEntity.STATUS_READY.equals(status)) {
                    return doc.get();
                }
                if (KbDocEntity.STATUS_FAILED.equals(status)) {
                    return doc.get(); // 交由断言带出 error 信息
                }
            }
            Thread.sleep(200);
        }
        String error = latest.get() == null ? "文档行不存在" : latest.get().getStatus();
        throw new AssertionError("等待超时（" + deadline + "），最后状态: " + error);
    }
}
