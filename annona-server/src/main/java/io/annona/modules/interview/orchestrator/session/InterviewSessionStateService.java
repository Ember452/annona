package io.annona.modules.interview.orchestrator.session;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public InterviewSessionStateService(InterviewSessionRepository sessionRepository,
                                        InterviewAnswerRepository answerRepository) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
    }

    /**
     * 建会话：同方向旧 RESUMABLE 自动置 ABANDONED（ADR 决策 5/M6），落会话行与整排 PENDING
     * 占位。{@code planJson} 是 Facade 序列化好的<b>组卷执行定稿快照</b>（plan + questionIds +
     * skippedReasons——T5 发现只存期望 plan 无法恢复出题顺序，快照扩为执行结果，单列自含，
     * 批 3 评估一次读全）；本方法不解析其内容，只守结构不变量：主问题槽数 == totalCount、
     * 槽位不重复、追问索引连续且不超占位上限。不符即 1001（防客户端拼装越界造会话）。
     *
     * <p>返回实体的 {@code createdAt} 为 null：DB default 列未回读（save/merge 语义，
     * AGENTS §4）；批 2 消费方（视图/缓存）不需要它，历史面板要时在此接返回值 refresh。
     *
     * @throws BusinessException 1001（槽位结构不符）
     */
    @Transactional
    public InterviewSessionEntity create(UUID userId, UUID directionId, String planJson,
                                         int totalCount, List<AnswerSlot> slots) {
        validateSlots(totalCount, slots);
        Instant now = Instant.now();
        sessionRepository.abandonAllResumable(userId, directionId, now);

        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setDirectionId(directionId);
        session.setStatus(InterviewSessionEntity.STATUS_RESUMABLE);
        session.setPlanJson(planJson);
        session.setCurrentIndex((short) 0);
        session.setTotalCount((short) totalCount);
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
    /**
     * 主问题作答后推进恢复位（条件 UPDATE：RESUMABLE 且只前进不回退）。
     * 放本服务而非 facade：@Modifying 必须在事务内执行（docker-it 实测 facade 直调
     * 抛 No active transaction），状态转移一律收口在 StateService。
     */
    @Transactional
    public boolean advanceIndex(UUID sessionId, short target) {
        return sessionRepository.advanceIndexIfResumable(sessionId, target, Instant.now()) > 0;
    }

    @Transactional
    public boolean abandon(UUID sessionId, UUID userId) {
        return sessionRepository.abandonIfResumable(sessionId, userId, Instant.now()) > 0;
    }

    private static void validateSlots(int totalCount, List<AnswerSlot> slots) {
        int mainCount = 0;
        Map<UUID, Integer> maxFollowLayer = new HashMap<>();
        Map<UUID, Integer> nonMainCount = new HashMap<>();
        Set<AnswerSlot> distinct = new HashSet<>();
        for (AnswerSlot slot : slots) {
            if (!distinct.add(slot)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "占位槽位重复：" + slot);
            }
            if (slot.followUpIndex() == 0) {
                mainCount++;
            } else {
                maxFollowLayer.merge(slot.questionId(), (int) slot.followUpIndex(), Math::max);
                nonMainCount.merge(slot.questionId(), 1, Integer::sum);
            }
        }
        if (mainCount != totalCount) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "主问题槽数 " + mainCount + " 与计划 " + totalCount + " 不符");
        }
        // 追问索引连续性：每题最大层号 == 该题非主槽数（1..k 无空洞；重复已在 distinct 拦下）
        maxFollowLayer.forEach((questionId, maxLayer) -> {
            if (!nonMainCount.get(questionId).equals(maxLayer)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "题目 " + questionId + " 的追问层不连续（最大 " + maxLayer + " 层，实有 "
                        + nonMainCount.get(questionId) + " 槽）");
            }
        });
    }
}
