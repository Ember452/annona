package io.annona.modules.plan.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.annona.modules.plan.entity.PlanTaskEntity;
import io.annona.modules.plan.repository.PlanTaskRepository;
import io.annona.shared.domain.CheckinLinkedEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 打卡联动瀑布切片（plan-module-adr §决策 3）：最旧优先、吃满即溢出、达标自动 DONE、
 * 方向不匹配不消费。真库行为由 {@code PlanFlowIT} 钉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("打卡联动瀑布切片")
class CheckinProgressListenerTest {

    @Mock
    private PlanTaskRepository taskRepository;

    @InjectMocks
    private CheckinProgressListener listener;

    private PlanTaskEntity task(String title, UUID directionId, int target, int progress) {
        PlanTaskEntity entity = new PlanTaskEntity();
        entity.setId(UUID.randomUUID());
        entity.setTitle(title);
        entity.setDirectionId(directionId);
        entity.setStatus(PlanTaskEntity.STATUS_PENDING);
        entity.setTargetMinutes(target);
        entity.setProgressMinutes(progress);
        entity.setPriority("normal");
        return entity;
    }

    @Test
    @DisplayName("50 分钟进两个 25 分钟任务：各自吃满并双双 DONE")
    void waterfallFillsOldestFirst() {
        UUID directionId = UUID.randomUUID();
        PlanTaskEntity oldest = task("旧任务", directionId, 25, 0);
        PlanTaskEntity newest = task("新任务", directionId, 25, 0);
        when(taskRepository.findByUserIdAndDirectionIdAndStatusOrderByCreatedAtAsc(
            any(), any(), any())).thenReturn(List.of(oldest, newest));

        listener.on(new CheckinLinkedEvent(UUID.randomUUID(), directionId, 50,
            LocalDate.now(), UUID.randomUUID()));

        assertThat(oldest.getProgressMinutes()).isEqualTo(25);
        assertThat(oldest.getStatus()).isEqualTo(PlanTaskEntity.STATUS_DONE);
        assertThat(newest.getProgressMinutes()).isEqualTo(25);
        assertThat(newest.getStatus()).isEqualTo(PlanTaskEntity.STATUS_DONE);
    }

    @Test
    @DisplayName("部分达标：第一个吃满 DONE，第二个只吃剩余；已满逻辑不重复给分")
    void partialOverflowStops() {
        UUID directionId = UUID.randomUUID();
        PlanTaskEntity first = task("任务一", directionId, 30, 10);
        PlanTaskEntity second = task("任务二", directionId, 25, 0);
        when(taskRepository.findByUserIdAndDirectionIdAndStatusOrderByCreatedAtAsc(
            any(), any(), any())).thenReturn(List.of(first, second));

        listener.on(new CheckinLinkedEvent(UUID.randomUUID(), directionId, 25,
            LocalDate.now(), UUID.randomUUID()));

        assertThat(first.getProgressMinutes()).isEqualTo(30);
        assertThat(first.getStatus()).isEqualTo(PlanTaskEntity.STATUS_DONE);
        assertThat(second.getProgressMinutes()).isEqualTo(5);
        assertThat(second.getStatus()).isEqualTo(PlanTaskEntity.STATUS_PENDING);
    }

    @Test
    @DisplayName("方向为 null 的事件直接忽略（防御性口径，正常打卡方向必填）")
    void nullDirectionIgnored() {
        listener.on(new CheckinLinkedEvent(UUID.randomUUID(), null, 30,
            LocalDate.now(), UUID.randomUUID()));
        org.mockito.Mockito.verifyNoInteractions(taskRepository);
    }
}
