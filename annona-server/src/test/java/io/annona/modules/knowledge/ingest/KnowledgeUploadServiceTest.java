package io.annona.modules.knowledge.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.storage.ObjectStorage;
import io.annona.modules.knowledge.dto.UploadResponse;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.listener.KnowledgeVectorizeStream;
import io.annona.modules.knowledge.repository.KbDocRepository;
import io.annona.shared.direction.service.DirectionQueryService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 上传编排单测——场景移植 🅖 KnowledgeBaseUploadServiceTest（请求线程不解析正文 /
 * 投递失败契约 / S3 孤儿补偿 / 重复上传契约）+ annona 特有：hash 幂等零消耗、方向归属校验。
 */
@DisplayName("KnowledgeUploadService：上传七步编排")
@ExtendWith(MockitoExtension.class)
class KnowledgeUploadServiceTest {

    /** 测试用 no-op 事务管理器：TransactionTemplate 只需要 getTransaction/commit 可调，回调照常执行。 */
    private static final PlatformTransactionManager TX_MGR = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };


    @Mock
    private KbDocRepository docRepository;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private KnowledgeVectorizeStream vectorizeStream;
    @Mock
    private DirectionQueryService directionQueryService;

    private KnowledgeUploadService service;

    private final String userId = UUID.randomUUID().toString();
    private final String directionId = UUID.randomUUID().toString();
    private byte[] content;

    @BeforeEach
    void setUp() {
        service = new KnowledgeUploadService(docRepository, Optional.of(objectStorage),
            vectorizeStream, directionQueryService,
            TX_MGR);
        content = "讲义内容".getBytes();
    }

    private void stubHappyDirection() {
        when(directionQueryService.existsVisibleTo(anyString(), anyString())).thenReturn(true);
    }

    @Nested
    @DisplayName("入参校验")
    class Validation {

        @Test
        @DisplayName("超过 50MB → 2301")
        void tooLarge() {
            byte[] huge = new byte[(int) (KnowledgeUploadService.MAX_FILE_SIZE + 1)];

            assertThatThrownBy(() -> service.upload(userId, huge, "a.pdf", directionId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                    assertThat(e.getCode()).isEqualTo(2301));
        }

        @Test
        @DisplayName("扩展名不在白名单 → 2302")
        void unsupportedType() {
            assertThatThrownBy(() -> service.upload(userId, content, "a.exe", directionId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                    assertThat(e.getCode()).isEqualTo(2302));
        }

        @Test
        @DisplayName("方向不可见 → 2100（不泄露他人方向存在性）")
        void directionNotVisible() {
            when(directionQueryService.existsVisibleTo(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> service.upload(userId, content, "a.md", directionId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                    assertThat(e.getCode()).isEqualTo(2100));
            verifyNoInteractions(objectStorage, vectorizeStream);
        }
    }

    @Nested
    @DisplayName("幂等与编排")
    class Orchestration {

        @Test
        @DisplayName("重复上传（同用户同内容 hash）→ duplicate=true 零消耗，不触碰 S3 与队列")
        void duplicateUploadIsFree() {
            stubHappyDirection();
            KbDocEntity existing = new KbDocEntity();
            existing.setId(UUID.randomUUID());
            existing.setStatus(KbDocEntity.STATUS_READY);
            when(docRepository.findByUserIdAndFileHash(any(), anyString()))
                .thenReturn(Optional.of(existing));

            UploadResponse response = service.upload(userId, content, "a.md", directionId);

            assertThat(response.duplicate()).isTrue();
            assertThat(response.id()).isEqualTo(existing.getId());
            verifyNoInteractions(objectStorage, vectorizeStream);
        }

        @Test
        @DisplayName("请求线程不解析正文：只做 hash/S3/落库/投递，响应即返回（借 🅖 shouldNotParseContentInRequestThread）")
        void requestThreadOnlyEnqueues() {
            stubHappyDirection();
            when(docRepository.findByUserIdAndFileHash(any(), anyString())).thenReturn(Optional.empty());
            when(docRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(vectorizeStream.send(any())).thenReturn(true);

            UploadResponse response = service.upload(userId, content, "讲义.md", directionId);

            assertThat(response.duplicate()).isFalse();
            assertThat(response.status()).isEqualTo(KbDocEntity.STATUS_PENDING);
            verify(objectStorage).put(anyString(), eq(content), anyString());
            verify(vectorizeStream).send(any());
        }

        @Test
        @DisplayName("投递失败 → 文档判 FAILED + 2307（文件与实体保留，借 🅖 shouldReturnFailedWhenEnqueueFails）")
        void enqueueFailureMarksFailed() {
            stubHappyDirection();
            when(docRepository.findByUserIdAndFileHash(any(), anyString())).thenReturn(Optional.empty());
            when(docRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(vectorizeStream.send(any())).thenReturn(false);

            assertThatThrownBy(() -> service.upload(userId, content, "a.md", directionId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                    assertThat(e.getCode()).isEqualTo(2307));
            verify(docRepository).markFailedIfPending(any(), anyString(), any());
        }

        @Test
        @DisplayName("DB 落库失败 → 补偿删除 S3 孤儿对象并原样上抛（借 🅖 shouldCompensateOrphanObjectOnDbFailure）")
        void dbFailureCompensatesOrphanObject() {
            stubHappyDirection();
            when(docRepository.findByUserIdAndFileHash(any(), anyString())).thenReturn(Optional.empty());
            when(docRepository.save(any())).thenThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> service.upload(userId, content, "a.md", directionId))
                .isInstanceOf(IllegalStateException.class);
            verify(objectStorage).delete(anyString());
        }

        @Test
        @DisplayName("对象存储未配置 → 2306（错误后移到使用点，启动不拦）")
        void storageNotConfigured() {
            KnowledgeUploadService withoutStorage = new KnowledgeUploadService(docRepository,
                Optional.empty(), vectorizeStream, directionQueryService, TX_MGR);
            when(directionQueryService.existsVisibleTo(anyString(), anyString())).thenReturn(true);

            assertThatThrownBy(() -> withoutStorage.upload(userId, content, "a.md", directionId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                    assertThat(e.getCode()).isEqualTo(2306));
        }
    }
}
