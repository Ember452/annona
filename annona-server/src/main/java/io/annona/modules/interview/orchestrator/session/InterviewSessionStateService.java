package io.annona.modules.interview.orchestrator.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.interview.orchestrator.plan.InterviewPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面试会话状态机编排（P1b-05）。所有终态写入走 repository 条件 UPDATE——本类没有
 * "find→setStatus→save" 的通道（interview-session-adr §状态机/§幂等）。
 *
 * <p>事务边界：每个公开方法 = 一个本地 DB 事务；组卷、嵌入、LLM 调用都在事务外
 * （AGENTS §AI And Async Rules），由调用方（T5 控制器/T4 组卷）在进入前完成。
 */
@Service
public class InterviewSessionStateService {

    /**
     * finalize 结果（批 2 只回落库事实；批 3 在此扩展评分重放）。
     *
     * @param sessionId        会话
     * @param answeredCount    交卷时处于 SUBMITTED 的槽位数（含弃答占位）
     * @param evaluatorVersion 评估器版本留痕（幂等键另一半）
     */
    public record FinalizeResult(UUID sessionId, int answeredCount, String evaluatorVersion) {
    }

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final ObjectMapper objectMapper;

    public InterviewSessionStateService(InterviewSessionRepository sessionRepository,
                                        InterviewAnswerRepository answerRepository) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 建会话：同方向旧 RESUMABLE 自动置 ABANDONED（ADR 决策 5/M6），落会话行与整排 PENDING
     * 占位。{@code slots} 必须是组卷结果按主问题+追问展平的原文——数量与 plan 的组合不符即
     * {@code 1001}（防客户端拼装越界造出"计划 2 题占位 50 槽"的会话）。
     *
     * <p>返回实体的 {@code createdAt} 为 null：DB default 列未回读（save/merge 语义，
     * AGENTS §4）；批 2 消费方（视图/缓存）不需要它，历史面板要时在此接返回值 refresh。
     *
     * @throws BusinessException 1001（槽数不符）
     */
    @Transactional
    public InterviewSessionEntity create(UUID userId, UUID directionId, InterviewPlan plan,
                                         List<AnswerSlot> slots) {
        int expected = plan.totalCount() * (plan.followUpDepth() + 1);
        if (slots.size() != expected) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "占位槽数 " + slots.size() + " 与计划不符（期望 " + expected + "）");
        }
        Instant now = Instant.now();
        sessionRepository.abandonAllResumable(userId, directionId, now);

        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setDirectionId(directionId);
        session.setStatus(InterviewSessionEntity.STATUS_RESUMABLE);
        session.setPlanJson(writePlan(plan));
        session.setCurrentIndex((short) 0);
        session.setTotalCount((short) plan.totalCount());
        session.setStartedAt(now);
        session.setUpdatedAt(now);
        sessionRepository.save(session);

        answerRepository.saveAll(slots.stream()
            .map(slot -> InterviewAnswerEntity.placeholder(session.getId(), slot, now))
            .toList());
        return session;
    }

    /**
     * 单题作答（条件 UPDATE 透传）。返回 false = 槽位不符/已提交/会话已终态，
     * 由控制器翻 2703 或"已答过"文案；本方法不抛业务异常。
     */
    @Transactional
    public boolean submitAnswer(UUID sessionId, UUID userId, UUID questionId,
                                int followUpIndex, String text) {
        return answerRepository.submitAnswer(sessionId, userId, questionId,
            (short) followUpIndex, text, Instant.now()) > 0;
    }

    /**
     * 交卷（幂等，ADR 决策 6/M8）：守门唯一是 {@code finalizeIfResumable} 的影响行数——
     * 赢者才批量置 SUBMITTED；并发败者（affected=0）重读状态给可读终态，绝不触碰作答行。
     *
     * <p>失败语义：会话不存在/非本人 → 2701；已 COMPLETED/ABANDONED → 2702（批 3 在此
     * 改为结果重放，语义不变：不产生双份记录）。事务只含这两条 UPDATE，评分异步在批 3。
     */
    @Transactional
    public FinalizeResult finalizeSession(UUID sessionId, UUID userId) {
        InterviewSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        if (InterviewSessionEntity.STATUS_COMPLETED.equals(session.getStatus())
            || InterviewSessionEntity.STATUS_ABANDONED.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_COMPLETED);
        }
        Instant now = Instant.now();
        int affected = sessionRepository.finalizeIfResumable(
            sessionId, userId, InterviewSessionEntity.EVALUATOR_VERSION_V1, now);
        if (affected == 0) {
            // 预读与转移之间被并发对手交卷：重读按真实终态给文案，作答行不动
            InterviewSessionEntity current = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
            throw new BusinessException(ErrorCode.SESSION_ALREADY_COMPLETED,
                InterviewSessionEntity.STATUS_ABANDONED.equals(current.getStatus())
                    ? "该面试已被放弃" : "该面试已交卷，请勿重复提交");
        }
        int submitted = answerRepository.markAllSubmitted(sessionId, now);
        return new FinalizeResult(sessionId, submitted,
            InterviewSessionEntity.EVALUATOR_VERSION_V1);
    }

    /** 手动放弃（M6 入口二）：仅 RESUMABLE 可弃。返回 false = 不存在或已终态。 */
    @Transactional
    public boolean abandon(UUID sessionId, UUID userId) {
        return sessionRepository.abandonIfResumable(sessionId, userId, Instant.now()) > 0;
    }

    private String writePlan(InterviewPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            // 计划由 record 定字段，序列化失败只可能是环境级 Jackson 故障，不静默降级
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组卷计划快照失败", e);
        }
    }
}
