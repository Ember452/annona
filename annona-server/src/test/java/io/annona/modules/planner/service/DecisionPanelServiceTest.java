package io.annona.modules.planner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.planner.reputation.RuleReputationService;
import io.annona.modules.planner.trace.DecisionTraceEntity;
import io.annona.modules.planner.trace.DecisionTraceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("DecisionPanelService 反驳与降权（P1c-07）")
class DecisionPanelServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID TRACE = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Mock
    private DecisionTraceRepository traceRepository;
    @Mock
    private RuleReputationService reputationService;

    private static DecisionTraceEntity trace(String ruleKey, String rejectedBy) {
        return DecisionTraceEntity.of(SESSION, USER, DIR, ruleKey, "RAISE_DIFFICULTY",
            "均分低加压", rejectedBy, "{}");
    }

    @Test
    @DisplayName("反驳一条未驳留痕：走条件 UPDATE 抢驳回权，再累计声誉并返回累计数")
    void rejectMarksAndCountsReputation() {
        var t = trace("WEAK_DIRECTION", null);
        when(traceRepository.findByIdAndUserId(TRACE, USER)).thenReturn(Optional.of(t));
        when(traceRepository.markRejectedIfOpen(TRACE, USER)).thenReturn(1);
        when(reputationService.recordRejection(eq(USER), eq("WEAK_DIRECTION"))).thenReturn(3);

        int count = new DecisionPanelService(traceRepository, reputationService)
            .reject(USER, TRACE);

        assertThat(count).isEqualTo(3);
        // 驳回与计数都在数据库侧完成（Java 不再改实体后写回）：归属条件不能漏 userId
        verify(traceRepository).markRejectedIfOpen(TRACE, USER);
    }

    @Test
    @DisplayName("并发抢驳（条件 UPDATE 影响 0 行）：同样出 3201，且不得再往声誉上叠计数")
    void concurrentLoserDoesNotDoubleCount() {
        var t = trace("WEAK_DIRECTION", null);
        when(traceRepository.findByIdAndUserId(TRACE, USER)).thenReturn(Optional.of(t));
        when(traceRepository.markRejectedIfOpen(TRACE, USER)).thenReturn(0);

        assertThatThrownBy(() -> new DecisionPanelService(traceRepository, reputationService)
            .reject(USER, TRACE))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.DECISION_ALREADY_REJECTED.getCode()));
        verify(reputationService, never()).recordRejection(any(), any());
    }

    @Test
    @DisplayName("重复驳回同一条 → 3201")
    void rejectTwiceRejectedThrows() {
        var t = trace("WEAK_DIRECTION", "USER");
        when(traceRepository.findByIdAndUserId(TRACE, USER)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> new DecisionPanelService(traceRepository, reputationService)
            .reject(USER, TRACE))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.DECISION_ALREADY_REJECTED.getCode()));
    }

    @Test
    @DisplayName("驳他人/不存在留痕 → 3200，不触发声誉累计")
    void rejectForeignThrows() {
        when(traceRepository.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new DecisionPanelService(traceRepository, reputationService)
            .reject(USER, TRACE))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.DECISION_NOT_FOUND.getCode()));
    }
}
