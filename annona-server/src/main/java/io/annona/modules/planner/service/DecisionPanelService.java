package io.annona.modules.planner.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.planner.dto.DecisionTraceResponse;
import io.annona.modules.planner.reputation.RuleReputationService;
import io.annona.modules.planner.trace.DecisionTraceEntity;
import io.annona.modules.planner.trace.DecisionTraceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可解释决策面板的读与反驳编排（P1c-06/07）。归属一律用 (id/direction, userId) 双条件——
 * 查不到即 {@code DECISION_NOT_FOUND}，不泄露他人决策是否存在（会话/评估同款口径）。
 *
 * <p>反驳是写路径：本方法在事务内改 decision_trace（置 USER 驳回 + 计数）并经
 * {@link RuleReputationService} 累计降权；不改面试结果、不改题库，只影响<b>后续</b>组卷
 * （advisor 下次装配时按停用键过滤规则，P1 出口③）。
 */
@Service
public class DecisionPanelService {

    private final DecisionTraceRepository traceRepository;
    private final RuleReputationService reputationService;

    public DecisionPanelService(DecisionTraceRepository traceRepository,
                                RuleReputationService reputationService) {
        this.traceRepository = traceRepository;
        this.reputationService = reputationService;
    }

    /** 单会话全部留痕（报告页决策理由节）；非本人会话返回空（不泄露存在性）。 */
    @Transactional(readOnly = true)
    public List<DecisionTraceResponse> sessionTraces(UUID userId, UUID sessionId) {
        return traceRepository.findBySessionIdAndUserIdOrderByRuleKeyAsc(sessionId, userId)
            .stream().map(DecisionTraceResponse::from).toList();
    }

    /** 首页"最近 N 场"决策摘要（时间倒序）；limit 夹在 [1,50] 防放大查询。 */
    @Transactional(readOnly = true)
    public List<DecisionTraceResponse> recentTraces(UUID userId, int limit) {
        int capped = Math.max(1, Math.min(50, limit));
        return traceRepository
            .findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, capped))
            .stream().map(DecisionTraceResponse::from).toList();
    }

    /**
     * 反驳一条决策留痕（P1c-07）。错误码：3200 不存在/非本人 / 3201 已驳回过。
     * 成功：置 trace 驳回 + 累计该规则声誉（达阈值停用）。返回停用后的累计驳回数。
     */
    @Transactional
    public int reject(UUID userId, UUID traceId) {
        DecisionTraceEntity trace = traceRepository.findByIdAndUserId(traceId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.DECISION_NOT_FOUND));
        if (trace.isUserRejected()) {
            throw new BusinessException(ErrorCode.DECISION_ALREADY_REJECTED);
        }
        trace.markUserRejected();
        return reputationService.recordRejection(userId, trace.getRuleKey());
    }
}
