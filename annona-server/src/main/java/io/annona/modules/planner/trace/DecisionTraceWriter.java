package io.annona.modules.planner.trace;

import io.annona.spi.dto.DecisionTrace;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 决策留痕写侧（P1c-05）：把一次组卷的规则链/guard 留痕落 decision_trace。
 *
 * <p>事务边界：{@code @Transactional} 只在 Service 层，纯本地库写（AGENTS §4）；sessionId 由
 * interview 侧会话创建后传入（跨模块写走"调用方已提交、本方法独立事务写自己的表"，不做跨模块
 * 同事务——interview 与 planner 各提交各的，trace 写失败不影响会话已建，面板降级显示"无决策记录"）。
 */
@Service
public class DecisionTraceWriter {

    private final DecisionTraceRepository repository;

    public DecisionTraceWriter(DecisionTraceRepository repository) {
        this.repository = repository;
    }

    /** 批量落一行条 trace（空列表静默返回，不产生 DB 往返）。 */
    @Transactional
    public void persist(UUID sessionId, UUID userId, UUID directionId,
                        List<DecisionTrace> traces, String inputSnapshotJson) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        repository.saveAll(traces.stream()
            .map(t -> DecisionTraceEntity.of(sessionId, userId, directionId,
                t.ruleKey(), t.action(), t.reason(), t.rejectedBy(), inputSnapshotJson))
            .toList());
    }
}
