package io.annona.modules.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.support.ContentHashes;
import io.annona.modules.plan.dto.SplitResponse;
import io.annona.modules.plan.entity.PlanEntity;
import io.annona.modules.plan.entity.PlanTaskEntity;
import io.annona.modules.plan.mapper.PlanMapperImpl;
import io.annona.modules.plan.repository.PlanRepository;
import io.annona.modules.plan.repository.PlanTaskRepository;
import io.annona.shared.ai.StructuredOutputInvoker;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PlanSplitService} 切片（plan-module-adr §决策 2）：指纹短路零 token、invoker
 * 缺失即 3302、normalize 非法值兜底、reconcile 写入只碰内容字段。模型用 mock
 * StructuredOutputInvoker；真库上的端到端链路由 PlanFlowIT（docker 组）钉。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanSplitService：指纹短路与归一化")
class PlanSplitServiceTest {

    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String DOCUMENT = "# 十月计划\n- 刷 JUC\n- 系统设计八股";

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PlanTaskRepository taskRepository;

    @Mock
    private ObjectProvider<StructuredOutputInvoker> invokerProvider;

    @Mock
    private StructuredOutputInvoker invoker;

    @Mock
    private PlatformTransactionManager transactionManager;

    private PlanSplitService service;
    private UUID planId;

    @BeforeEach
    void setUp() {
        service = new PlanSplitService(planRepository, taskRepository, invokerProvider,
            new PlanMapperImpl(), transactionManager);
        planId = UUID.randomUUID();
    }

    private PlanEntity plan(String sourceHash) {
        PlanEntity entity = new PlanEntity();
        entity.setId(planId);
        entity.setUserId(UUID.fromString(OWNER));
        entity.setTitle("十月计划");
        entity.setDocument(DOCUMENT);
        entity.setSourceHash(sourceHash);
        return entity;
    }

    private PlanEntity managedPlan() {
        PlanEntity entity = plan(null);
        when(planRepository.findByIdAndUserId(planId, UUID.fromString(OWNER)))
            .thenReturn(Optional.of(entity));
        return entity;
    }

    @Nested
    @DisplayName("split：短路闸门")
    class Gate {

        @Test
        @DisplayName("文档指纹未变 → applied=false 直接回现任务，不碰模型不花 token")
        void shortCircuitsWhenHashUnchanged() {
            String hash = ContentHashes.sha256Hex(DOCUMENT);
            when(planRepository.findByIdAndUserId(planId, UUID.fromString(OWNER)))
                .thenReturn(Optional.of(plan(hash)));
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(planId)).thenReturn(List.of());

            SplitResponse response = service.split(OWNER, planId);

            assertThat(response.applied()).isFalse();
            verify(invokerProvider, never()).getIfAvailable();
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("模型未装配（ObjectProvider 空）→ 3302，不动任务表")
        void unavailableWhenInvokerMissing() {
            managedPlan();
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(planId)).thenReturn(List.of());
            when(invokerProvider.getIfAvailable()).thenReturn(null);

            assertThatThrownBy(() -> service.split(OWNER, planId))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(3302));
            verify(taskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("split：正常拆分链路")
    class HappyPath {

        @Test
        @DisplayName("模型返回非法值 → normalize 兜底后可用（category/priority/target 全在域内）")
        void normalizesModelOutput() {
            managedPlan();
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(planId)).thenReturn(List.of());
            when(invokerProvider.getIfAvailable()).thenReturn(invoker);
            when(invokerProvider.getObject()).thenReturn(invoker);
            when(invoker.invoke(anyString(), anyString(), eq(PlanSplitService.SplitResult.class)))
                .thenReturn(new PlanSplitService.SplitResult(List.of(
                    new PlanSplitService.SplitTask("  背八股  ", null, "napping", "urgent", 900))));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.split(OWNER, planId);

            org.mockito.ArgumentCaptor<PlanTaskEntity> captor =
                org.mockito.ArgumentCaptor.forClass(PlanTaskEntity.class);
            verify(taskRepository).save(captor.capture());
            PlanTaskEntity saved = captor.getValue();
            assertThat(saved.getTitle()).isEqualTo("背八股");
            assertThat(saved.getCategory()).isEqualTo("study");
            assertThat(saved.getPriority()).isEqualTo("normal");
            assertThat(saved.getTargetMinutes()).isEqualTo(600);
            assertThat(saved.getSource()).isEqualTo(PlanTaskEntity.SOURCE_AI);
        }

        @Test
        @DisplayName("匹配任务只更新内容字段，status/progress/source 不动（ADR §决策 2）")
        void updateKeepsStatusAndProgress() {
            managedPlan();
            PlanTaskEntity existingTask = new PlanTaskEntity();
            existingTask.setId(UUID.randomUUID());
            existingTask.setPlanId(planId);
            existingTask.setUserId(UUID.fromString(OWNER));
            existingTask.setTitle("刷 JUC");
            existingTask.setStatus(PlanTaskEntity.STATUS_DONE);
            existingTask.setProgressMinutes(50);
            existingTask.setTargetMinutes(25);
            existingTask.setSource(PlanTaskEntity.SOURCE_AI);
            existingTask.setDescription("旧描述");
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(planId))
                .thenReturn(List.of(existingTask), List.of(existingTask));
            when(invokerProvider.getIfAvailable()).thenReturn(invoker);
            when(invokerProvider.getObject()).thenReturn(invoker);
            when(invoker.invoke(anyString(), anyString(), eq(PlanSplitService.SplitResult.class)))
                .thenReturn(new PlanSplitService.SplitResult(List.of(
                    new PlanSplitService.SplitTask("刷 JUC", "新描述", "review", "high", 40))));

            service.split(OWNER, planId);

            assertThat(existingTask.getDescription()).isEqualTo("新描述");
            assertThat(existingTask.getPriority()).isEqualTo("high");
            // 进度与完成态是用户资产：重拆永不回退
            assertThat(existingTask.getStatus()).isEqualTo(PlanTaskEntity.STATUS_DONE);
            assertThat(existingTask.getProgressMinutes()).isEqualTo(50);
            assertThat(existingTask.getSource()).isEqualTo(PlanTaskEntity.SOURCE_AI);
        }

        @Test
        @DisplayName("拆分成功后回写文档指纹（下次同文档短路）")
        void writesBackFingerprint() {
            PlanEntity entity = managedPlan();
            when(taskRepository.findByPlanIdOrderByCreatedAtAsc(planId)).thenReturn(List.of());
            when(invokerProvider.getIfAvailable()).thenReturn(invoker);
            when(invokerProvider.getObject()).thenReturn(invoker);
            when(invoker.invoke(anyString(), anyString(), eq(PlanSplitService.SplitResult.class)))
                .thenReturn(new PlanSplitService.SplitResult(List.of()));
            when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.split(OWNER, planId);

            assertThat(entity.getSourceHash()).isEqualTo(ContentHashes.sha256Hex(DOCUMENT));
        }
    }

    /**
     * 事务边界机检（AGENTS §0.3“LLM 不入事务”+ §4“约定→机检升级”元规则）：
     * split() 一旦重新挂上 @Transactional，方法体外调模型就在事务内——重试期间持有
     * DB 连接，池 exhaustion 只在生产高峰暴露，slice 测的 mock 探不到，故用反射钉死。
     * 同族先例：PropertiesDefaultSourceTest。
     */
    @Nested
    @DisplayName("事务边界守卫（防 Javadoc 与实现再次漂移）")
    class TransactionBoundary {

        @Test
        @DisplayName("split() 方法上禁止 @Transactional（模型调用必须发生在事务外）")
        void splitMustNotBeTransactional() throws NoSuchMethodException {
            Method split = PlanSplitService.class
                .getMethod("split", String.class, UUID.class);

            assertThat(split.getAnnotation(Transactional.class))
                .as("split() 带 @Transactional = LLM 调用进事务（AGENTS §0.3），"
                    + "写入短事务由 TransactionTemplate 承担，不要改回注解")
                .isNull();
        }
    }
}
