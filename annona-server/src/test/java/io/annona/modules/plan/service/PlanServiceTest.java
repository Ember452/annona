package io.annona.modules.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.modules.plan.dto.CreatePlanRequest;
import io.annona.modules.plan.dto.CreateTaskRequest;
import io.annona.modules.plan.dto.PatchTaskRequest;
import io.annona.modules.plan.dto.PlanDetailResponse;
import io.annona.modules.plan.dto.PlanTaskResponse;
import io.annona.modules.plan.dto.UpdatePlanRequest;
import io.annona.modules.plan.entity.PlanEntity;
import io.annona.modules.plan.entity.PlanTaskEntity;
import io.annona.modules.plan.mapper.PlanMapperImpl;
import io.annona.modules.plan.repository.PlanRepository;
import io.annona.modules.plan.repository.PlanTaskRepository;
import io.annona.shared.direction.service.DirectionQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PlanService} 切片（plan-module-adr）：owner 隔离取数（查不到即 3300 不泄露
 * 存在性）、入参闸门、方向缺省继承、文档变更作废拆分指纹、今日待办三档优先级排序。
 * repositories / DirectionQueryService 全 mock，mapper 用 MapStruct 真实现。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanService：计划 CRUD 与今日待办")
class PlanServiceTest {

    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "99999999-9999-9999-9999-999999999999";
    private static final String DIRECTION_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PlanTaskRepository taskRepository;

    @Mock
    private DirectionQueryService directions;

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(planRepository, taskRepository, directions, new PlanMapperImpl());
    }

    private PlanEntity plan(UUID id, UUID directionId) {
        PlanEntity entity = new PlanEntity();
        entity.setId(id);
        entity.setUserId(UUID.fromString(OWNER));
        entity.setDirectionId(directionId);
        entity.setTitle("秋招计划");
        entity.setDocument("# 计划");
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private PlanTaskEntity task(String title, String priority, Instant createdAt) {
        PlanTaskEntity entity = new PlanTaskEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(UUID.fromString(OWNER));
        entity.setTitle(title);
        entity.setPriority(priority);
        entity.setStatus(PlanTaskEntity.STATUS_PENDING);
        entity.setCategory("study");
        entity.setTargetMinutes(25);
        entity.setProgressMinutes(0);
        entity.setSource(PlanTaskEntity.SOURCE_MANUAL);
        entity.setCreatedAt(createdAt);
        return entity;
    }

    @Nested
    @DisplayName("create：入参闸门与方向校验")
    class Create {

        @Test
        @DisplayName("空白标题 → 1001，不触库")
        void rejectsBlankTitle() {
            assertThatThrownBy(() -> service.create(OWNER, new CreatePlanRequest("   ", null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(1001));
            verifyNoRepositoryWrites();
        }

        @Test
        @DisplayName("文档超 200k 字符 → 1001")
        void rejectsOversizedDocument() {
            assertThatThrownBy(() -> service.create(OWNER,
                new CreatePlanRequest("标题", null, "x".repeat(200_001))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(1001));
            verifyNoRepositoryWrites();
        }

        @Test
        @DisplayName("方向对本人不可见 → 2100（DIRECTION_NOT_FOUND），不落库")
        void rejectsInvisibleDirection() {
            when(directions.existsVisibleTo(OWNER, DIRECTION_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.create(OWNER,
                new CreatePlanRequest("标题", DIRECTION_ID, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(2100));
            verify(planRepository, never()).save(any());
        }

        @Test
        @DisplayName("无方向也可建（可选归属）：document 缺省为空串")
        void createsWithoutDirection() {
            // create() 自行生成主键并用它回查 detail；用 holder 接住被保存的实体，
            // 令 save 与 findByIdAndUserId 返回同一实例（不预先固定 UUID 否则严格桩失配）
            java.util.concurrent.atomic.AtomicReference<PlanEntity> savedRef =
                new java.util.concurrent.atomic.AtomicReference<>();
            when(planRepository.save(any())).thenAnswer(inv -> {
                savedRef.set(inv.getArgument(0));
                return inv.getArgument(0);
            });
            when(planRepository.findByIdAndUserId(any(), any()))
                .thenAnswer(inv -> Optional.of(savedRef.get()));
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

            PlanDetailResponse response = service.create(OWNER,
                new CreatePlanRequest("  秋招计划  ", null, null));

            ArgumentCaptor<PlanEntity> captor = ArgumentCaptor.forClass(PlanEntity.class);
            verify(planRepository).save(captor.capture());
            assertThat(captor.getValue().getDocument()).isEmpty();
            assertThat(captor.getValue().getTitle()).isEqualTo("秋招计划");
            assertThat(response.id()).isEqualTo(savedRef.get().getId().toString());
        }
    }

    @Nested
    @DisplayName("update / detail / delete：owner 隔离")
    class Ownership {

        @Test
        @DisplayName("他人计划取不到 → 3300，不泄露存在性")
        void otherUsersPlanIsNotFound() {
            UUID planId = UUID.randomUUID();
            when(planRepository.findByIdAndUserId(planId, UUID.fromString(OTHER)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.detail(OTHER, planId))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(3300));
        }

        @Test
        @DisplayName("文档变更 → source_hash 作废置空（下次 split 重算指纹，ADR §决策 2）")
        void documentChangeResetsFingerprint() {
            UUID planId = UUID.randomUUID();
            PlanEntity entity = plan(planId, null);
            entity.setSourceHash("stale-hash");
            when(planRepository.findByIdAndUserId(planId, UUID.fromString(OWNER)))
                .thenReturn(Optional.of(entity));
            when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(planId)).thenReturn(List.of());

            service.update(OWNER, planId, new UpdatePlanRequest(null, "# 新文档"));

            ArgumentCaptor<PlanEntity> captor = ArgumentCaptor.forClass(PlanEntity.class);
            verify(planRepository).save(captor.capture());
            assertThat(captor.getValue().getSourceHash()).isNull();
            assertThat(captor.getValue().getDocument()).isEqualTo("# 新文档");
        }

        @Test
        @DisplayName("仅改标题不动指纹")
        void titleOnlyKeepsFingerprint() {
            UUID planId = UUID.randomUUID();
            PlanEntity entity = plan(planId, null);
            entity.setSourceHash("keep-me");
            when(planRepository.findByIdAndUserId(planId, UUID.fromString(OWNER)))
                .thenReturn(Optional.of(entity));
            when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(planId)).thenReturn(List.of());

            service.update(OWNER, planId, new UpdatePlanRequest("新标题", null));

            assertThat(entity.getSourceHash()).isEqualTo("keep-me");
            assertThat(entity.getTitle()).isEqualTo("新标题");
        }

        @Test
        @DisplayName("detail：从未拆分（指纹空）且无任务 → stale=true 提示重拆")
        void detailFlagsNeverSplitPlan() {
            UUID planId = UUID.randomUUID();
            when(planRepository.findByIdAndUserId(planId, UUID.fromString(OWNER)))
                .thenReturn(Optional.of(plan(planId, null)));
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(planId)).thenReturn(List.of());

            assertThat(service.detail(OWNER, planId).stale()).isTrue();
        }
    }

    @Nested
    @DisplayName("addTask / patchTask：方向继承与状态白名单")
    class Tasks {

        @Test
        @DisplayName("任务方向缺省继承计划归属（JSON 无法区分缺省与显式 null，ADR §后果）")
        void addTaskInheritsPlanDirection() {
            UUID planId = UUID.randomUUID();
            UUID directionId = UUID.fromString(DIRECTION_ID);
            when(planRepository.findByIdAndUserId(planId, UUID.fromString(OWNER)))
                .thenReturn(Optional.of(plan(planId, directionId)));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.addTask(OWNER, planId, new CreateTaskRequest("刷 JUC 源码", null, null));

            ArgumentCaptor<PlanTaskEntity> captor = ArgumentCaptor.forClass(PlanTaskEntity.class);
            verify(taskRepository).save(captor.capture());
            PlanTaskEntity saved = captor.getValue();
            assertThat(saved.getDirectionId()).isEqualTo(directionId);
            assertThat(saved.getTargetMinutes()).isEqualTo(25);
            assertThat(saved.getSource()).isEqualTo(PlanTaskEntity.SOURCE_MANUAL);
        }

        @Test
        @DisplayName("显式 targetMinutes 越界被夹到 5..600")
        void addTargetMinutesIsClamped() {
            UUID planId = UUID.randomUUID();
            when(planRepository.findByIdAndUserId(planId, UUID.fromString(OWNER)))
                .thenReturn(Optional.of(plan(planId, null)));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.addTask(OWNER, planId, new CreateTaskRequest("晨读", null, 900));

            ArgumentCaptor<PlanTaskEntity> captor = ArgumentCaptor.forClass(PlanTaskEntity.class);
            verify(taskRepository).save(captor.capture());
            assertThat(captor.getValue().getTargetMinutes()).isEqualTo(600);
        }

        @Test
        @DisplayName("patchTask 非法状态 → 1001，不查库")
        void patchTaskRejectsInvalidStatus() {
            assertThatThrownBy(() -> service.patchTask(OWNER, UUID.randomUUID(),
                UUID.randomUUID(), new PatchTaskRequest("ARCHIVED")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(1001));
            verifyNoInteractions(taskRepository);
        }

        @Test
        @DisplayName("patchTask 越权任务（plan+user 双重归属）→ 3301")
        void patchTaskForeignUserTaskNotFound() {
            when(taskRepository.findByIdAndPlanIdAndUserId(any(), any(), any()))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.patchTask(OWNER, UUID.randomUUID(),
                UUID.randomUUID(), new PatchTaskRequest(PlanTaskEntity.STATUS_DONE)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(3301));
        }
    }

    @Nested
    @DisplayName("today：跨计划待办排序")
    class Today {

        @Test
        @DisplayName("priority high→normal→low，同级按创建序（DB 字母序与语义序不一致，内存定序）")
        void sortsByPriorityThenCreation() {
            Instant base = Instant.parse("2026-10-01T00:00:00Z");
            List<PlanTaskEntity> rows = List.of(
                task("低优先", "low", base.minusSeconds(100)),
                task("高优先后建", "high", base),
                task("普通", "normal", base.minusSeconds(50)),
                task("高优先先建", "high", base.minusSeconds(20)));
            when(taskRepository.findByUserIdAndStatusOrderByCreatedAtAsc(
                UUID.fromString(OWNER), PlanTaskEntity.STATUS_PENDING)).thenReturn(rows);

            List<String> titles = service.today(OWNER).stream().map(PlanTaskResponse::title).toList();

            assertThat(titles).containsExactly("高优先先建", "高优先后建", "普通", "低优先");
        }
    }

    private void verifyNoRepositoryWrites() {
        verify(planRepository, never()).save(any());
    }
}
