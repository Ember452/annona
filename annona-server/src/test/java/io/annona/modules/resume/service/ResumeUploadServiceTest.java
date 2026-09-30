package io.annona.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.storage.ObjectStorage;
import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.resume.dto.ResumeUploadResponse;
import io.annona.modules.resume.entity.ResumeEntity;
import io.annona.modules.resume.repository.ResumeRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 上传编排的分支语义（大小/类型校验、hash 幂等、投递失败兜底），S3/流真接线归 docker-it。 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ResumeUploadService：校验、幂等、投递失败兜底")
class ResumeUploadServiceTest {

    @Mock
    private ResumeRepository resumeRepository;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private TaskStreamPort taskStreamPort;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ResumeUploadService service;
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ResumeUploadService(resumeRepository, Optional.of(objectStorage),
            taskStreamPort, transactionManager);
    }

    @Test
    @DisplayName("空文件 → 3102 类型不支持")
    void emptyRejected() {
        assertThatThrownBy(() -> service.upload(userId.toString(), new byte[0], "r.pdf"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESUME_TYPE_NOT_SUPPORTED.getCode()));
    }

    @Test
    @DisplayName("扩展名不在白名单（.exe）→ 3102，且不落存储")
    void badExtensionRejected() {
        assertThatThrownBy(() -> service.upload(userId.toString(), new byte[] {1, 2, 3}, "virus.exe"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESUME_TYPE_NOT_SUPPORTED.getCode()));
        verify(objectStorage, never()).put(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("同内容重复上传 → duplicate=true，零存储写入零投递（hash 幂等）")
    void duplicateReturnsExisting() {
        ResumeEntity existing = new ResumeEntity();
        existing.setId(UUID.randomUUID());
        existing.setStatus(ResumeEntity.STATUS_DONE);
        when(resumeRepository.findByUserIdAndFileHash(eq(userId), anyString()))
            .thenReturn(Optional.of(existing));

        ResumeUploadResponse r = service.upload(userId.toString(), "同一份简历".getBytes(), "a.pdf");

        assertThat(r.duplicate()).isTrue();
        assertThat(r.status()).isEqualTo(ResumeEntity.STATUS_DONE);
        verify(objectStorage, never()).put(anyString(), any(), anyString());
        verify(taskStreamPort, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("新简历：存储→落库→投递成功 → PENDING")
    void freshUploadEnqueues() {
        when(resumeRepository.findByUserIdAndFileHash(eq(userId), anyString()))
            .thenReturn(Optional.empty());
        when(taskStreamPort.send(anyString(), any())).thenReturn(true);

        ResumeUploadResponse r = service.upload(userId.toString(), "简历正文".getBytes(), "a.pdf");

        assertThat(r.duplicate()).isFalse();
        assertThat(r.status()).isEqualTo(ResumeEntity.STATUS_PENDING);
        verify(resumeRepository).save(any(ResumeEntity.class));
    }

    @Test
    @DisplayName("投递失败：置 FAILED 兜底并抛 3104（不留待会重复处理的在途行）")
    void enqueueFailureMarksFailed() {
        when(resumeRepository.findByUserIdAndFileHash(eq(userId), anyString()))
            .thenReturn(Optional.empty());
        when(taskStreamPort.send(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.upload(userId.toString(), "简历".getBytes(), "a.pdf"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESUME_ENQUEUE_FAILED.getCode()));
        verify(resumeRepository).markFailed(any(UUID.class), anyString(), any(Instant.class));
    }
}
