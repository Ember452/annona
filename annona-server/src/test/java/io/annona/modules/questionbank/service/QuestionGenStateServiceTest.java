package io.annona.modules.questionbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.questionbank.entity.QbGenerationTaskEntity;
import io.annona.modules.questionbank.model.QuestionGenConfig;
import io.annona.modules.questionbank.repository.QbGenerationTaskRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 出题任务状态机编排：在途互斥、条件转移的透传语义（fencing 本体在 repository SQL，docker-it 钉真库）。 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
class QuestionGenStateServiceTest {

    @Mock
    private QbGenerationTaskRepository repository;

    @InjectMocks
    private QuestionGenStateService service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID DIRECTION = UUID.randomUUID();
    private static final QuestionGenConfig CONFIG = new QuestionGenConfig(3, 10, 2);

    @Test
    @DisplayName("创建任务：无在途时落 QUEUED 并带参数快照")
    void createTaskQueued() {
        when(repository.existsByUserIdAndDirectionIdAndStatusIn(any(), any(), any())).thenReturn(false);

        service.createTask(USER, DIRECTION, CONFIG);

        ArgumentCaptor<QbGenerationTaskEntity> captor =
            ArgumentCaptor.forClass(QbGenerationTaskEntity.class);
        verify(repository).save(captor.capture());
        QbGenerationTaskEntity saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(QbGenerationTaskEntity.STATUS_QUEUED);
        assertThat(saved.getConfig()).isEqualTo(CONFIG);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("在途任务存在即 2600，不再落库")
    void createTaskConflictsWithInflight() {
        when(repository.existsByUserIdAndDirectionIdAndStatusIn(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.createTask(USER, DIRECTION, CONFIG))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.QB_GENERATION_TASK_IN_FLIGHT.getCode()));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("条件转移透传影响行数：>0 为成功")
    void transitionsPassThroughRowsAffected() {
        UUID taskId = UUID.randomUUID();
        when(repository.tryMarkProcessing(eq(taskId), any())).thenReturn(1);
        when(repository.markCompleted(eq(taskId), eq(8), eq(2), eq("已生成 8 题，跳过 2 道重复"),
            any())).thenReturn(1);

        assertThat(service.tryMarkProcessing(taskId)).isTrue();
        assertThat(service.markCompleted(taskId, 8, 2, "已生成 8 题，跳过 2 道重复")).isTrue();
    }

    @Test
    @DisplayName("条件不匹配（0 行）返回 false——已被其他实例领取或状态已变，调用方安静放弃")
    void transitionsFalseOnZeroRows() {
        UUID taskId = UUID.randomUUID();
        when(repository.tryMarkProcessing(eq(taskId), any())).thenReturn(0);
        when(repository.markFailed(eq(taskId), eq("boom"), any())).thenReturn(0);
        when(repository.resetForRetry(eq(taskId), any())).thenReturn(0);

        assertThat(service.tryMarkProcessing(taskId)).isFalse();
        assertThat(service.markFailed(taskId, "boom")).isFalse();
        assertThat(service.resetForRetry(taskId)).isFalse();
    }
}
