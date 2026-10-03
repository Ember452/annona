package io.annona.modules.plan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.result.Result;
import io.annona.modules.plan.dto.CreatePlanRequest;
import io.annona.modules.plan.dto.PlanDetailResponse;
import io.annona.modules.plan.dto.SplitResponse;
import io.annona.modules.plan.service.PlanService;
import io.annona.modules.plan.service.PlanSplitService;
import io.annona.modules.plan.service.StudioChatService;
import io.annona.spi.dto.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PlanController} 切片：只做路由、委托与 Result 包裹（AGENTS §分层），
 * 业务规则归 PlanServiceTest / PlanSplitServiceTest。SSE 流的端到端形状由
 * PlanFlowIT（docker 组）经真 HTTP 钉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("计划端点：委托与统一响应形状")
class PlanControllerTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final Principal PRINCIPAL = new Principal(USER_ID, "local", Set.of("USER"));

    @Mock
    private PlanService planService;

    @Mock
    private PlanSplitService splitService;

    private PlanController controller;

    @BeforeEach
    void setUp() {
        controller = new PlanController(planService, splitService, mock(StudioChatService.class));
    }

    @Test
    @DisplayName("create：principal.id 透传为 userId，Result.success 包裹服务返回")
    void createDelegatesWithPrincipalId() {
        UUID planId = UUID.randomUUID();
        PlanDetailResponse detail = new PlanDetailResponse(planId.toString(), "秋招计划", null,
            "# doc", false, List.of(), Instant.now());
        when(planService.create(eq(USER_ID), any(CreatePlanRequest.class))).thenReturn(detail);

        Result<PlanDetailResponse> result =
            controller.create(PRINCIPAL, new CreatePlanRequest("秋招计划", null, "# doc"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isSameAs(detail);
        verify(planService).create(USER_ID, new CreatePlanRequest("秋招计划", null, "# doc"));
    }

    @Test
    @DisplayName("split：路径 id 解析为 UUID 后委托，返回 applied 标记")
    void splitParsesPathIdToUuid() {
        UUID planId = UUID.randomUUID();
        SplitResponse response = new SplitResponse(List.of(), true);
        when(splitService.split(USER_ID, planId)).thenReturn(response);

        Result<SplitResponse> result = controller.split(PRINCIPAL, planId.toString());

        assertThat(result.getData()).isSameAs(response);
        assertThat(result.getData().applied()).isTrue();
    }

    @Test
    @DisplayName("delete：服务 void 返回也走 Result.success 统一形状")
    void deleteWrapsVoidSuccess() {
        UUID planId = UUID.randomUUID();

        Result<Void> result = controller.delete(PRINCIPAL, planId.toString());

        verify(planService).delete(eq(USER_ID), eq(planId));
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("门控注解存在（annona.plan.enabled 全关时端点整体消失）")
    void gatedByPlanSwitch() {
        var gate = PlanController.class
            .getAnnotation(org.springframework.boot.autoconfigure.condition
                .ConditionalOnProperty.class);

        assertThat(gate).isNotNull();
        assertThat(gate.value()).containsExactly("annona.plan.enabled");
        assertThat(gate.havingValue()).isEqualTo("true");
        assertThat(gate.matchIfMissing()).isTrue();
    }
}
