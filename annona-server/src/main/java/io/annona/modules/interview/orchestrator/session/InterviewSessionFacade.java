package io.annona.modules.interview.orchestrator.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.cache.SessionSnapshotPort;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.support.AppZones;
import io.annona.modules.interview.orchestrator.controller.CreateSessionRequest;
import io.annona.modules.interview.orchestrator.controller.FinalizeView;
import io.annona.modules.interview.orchestrator.controller.SessionSummary;
import io.annona.modules.interview.orchestrator.controller.SessionView;
import io.annona.modules.interview.orchestrator.controller.SlotView;
import io.annona.modules.interview.orchestrator.pack.QuestionDedupService;
import io.annona.modules.interview.orchestrator.pack.QuestionPackService;
import io.annona.modules.interview.orchestrator.plan.InterviewPlan;
import io.annona.modules.planner.advisor.PlannerAdvisorService;
import io.annona.modules.planner.advisor.PlanDecision;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.question.QuestionCandidate;
import io.annona.shared.question.QuestionQueryService;
import io.annona.shared.question.QuestionStemDetail;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 面试会话编排（P1b-04/05 端点侧）：校验 → 组卷 → 落会话 → 视图装配 → 快照维护。
 * Controller 只做路由与委托，事务与状态转移全部在 {@link InterviewSessionStateService}。
 *
 * <p>冷热分层读路径（interview-session-adr §冷热分层）：每次读先做 DB 行级归属校验
 * （防缓存被猜 id 直读泄漏他人视图），快照只省"slots+题干装配"这段聚合成本；写路径
 * DB 先行、快照尽力 evict（Redis 缺席/故障照常工作，24h TTL 兜底陈旧）。
 *
 * <p>容量口径（M7）：题池为空或组卷颗粒无收 → 2604（复用批 1 语义）；组卷有题但有缺口
 * 照常开面，缺口以 skippedReasons 随视图返回——"能开就开，如实告知"优于拒绝。
 */
@Service
public class InterviewSessionFacade {

    private static final Logger log = LoggerFactory.getLogger(InterviewSessionFacade.class);

    /** plan JSONB 的落库形态：期望计划 + 组卷执行结果（单列自含，批 3 评估一次读全）。 */
    record PackSnapshot(int totalCount, List<Integer> difficulties, int followUpDepth,
                        List<UUID> questionIds, List<String> skippedReasons) {

        PackSnapshot {
            // 反序列化容错：历史/残缺 JSON 的 null 列表归一为空集（视图降级空题序，不 NPE）
            difficulties = difficulties == null ? List.of() : List.copyOf(difficulties);
            questionIds = questionIds == null ? List.of() : List.copyOf(questionIds);
            skippedReasons = skippedReasons == null ? List.of() : List.copyOf(skippedReasons);
        }
    }

    private final InterviewSessionStateService stateService;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final DirectionQueryService directionQuery;
    private final QuestionQueryService questionQuery;
    private final QuestionDedupService dedupService;
    private final QuestionPackService packService;
    private final ObjectProvider<SessionSnapshotPort> snapshots;
    private final ObjectProvider<PlannerAdvisorService> advisors;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterviewSessionFacade(InterviewSessionStateService stateService,
                                  InterviewSessionRepository sessionRepository,
                                  InterviewAnswerRepository answerRepository,
                                  DirectionQueryService directionQuery,
                                  QuestionQueryService questionQuery,
                                  QuestionDedupService dedupService,
                                  QuestionPackService packService,
                                  ObjectProvider<SessionSnapshotPort> snapshots,
                                  ObjectProvider<PlannerAdvisorService> advisors) {
        this.stateService = stateService;
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.directionQuery = directionQuery;
        this.questionQuery = questionQuery;
        this.dedupService = dedupService;
        this.packService = packService;
        this.snapshots = snapshots;
        this.advisors = advisors;
    }

    /**
     * 开始面试：计划校验（1001）→ 方向可见（2100）→ 决策组卷（auto 走 planner，manual/降级走原难度）
     * → 容量不足 2604 → 同方向旧会话自动 ABANDONED → 落库并写快照 → auto 时落决策留痕。
     *
     * <p>决策接线（P1c-05）：{@code planMode!=manual} 且有 advisor 时，用请求难度作基线交 planner
     * 调整得到难度序列 + 复习题 ID，组卷后携 sessionId 落 decision_trace。advisor 抛错则 catch+log.warn
     * 降级为请求原难度、skippedReasons 记“本次由默认策略出题”——<b>决策失败绝不阻断开面</b>。
     */
    public SessionView create(UUID userId, CreateSessionRequest request) {
        InterviewPlan baseline;
        try {
            baseline = new InterviewPlan(request.totalCount(), request.difficulties(),
                request.followUpDepth());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "组卷计划不合法：" + (e.getMessage() == null ? "字段缺失" : e.getMessage()));
        }
        if (!directionQuery.existsVisibleTo(userId.toString(), request.directionId())) {
            throw new BusinessException(ErrorCode.DIRECTION_NOT_FOUND);
        }
        UUID directionId = UUID.fromString(request.directionId());
        List<QuestionCandidate> pool = questionQuery.activePool(userId, directionId);
        if (pool.isEmpty()) {
            throw new BusinessException(ErrorCode.QB_QUESTION_CAPACITY_INSUFFICIENT);
        }

        boolean manual = "manual".equalsIgnoreCase(request.planMode());
        PlanDecision decision = manual ? null : tryAdvise(userId, directionId, baseline);
        List<String> extraNotes = new ArrayList<>();
        InterviewPlan plan = baseline;
        Set<UUID> reviewIds = Set.of();
        if (!manual) {
            if (decision != null) {
                plan = new InterviewPlan(baseline.totalCount(), decision.difficulties(),
                    baseline.followUpDepth());
                reviewIds = new HashSet<>(decision.reviewQuestionIds());
            } else {
                extraNotes.add("本次由默认策略出题（决策层未参与）");
            }
        }

        var pack = packService.pack(plan, pool,
            dedupService.findDedupHits(userId, directionId, pool), reviewIds);
        // 留痕按实际卷面校正（诚实呈现：不能承诺没做到的事）
        decision = reconcileReview(decision, pack.questionIds());
        if (pack.questionIds().isEmpty()) {
            throw new BusinessException(ErrorCode.QB_QUESTION_CAPACITY_INSUFFICIENT,
                "近 90 天该方向题目已答过或被去重拦截，请先补充题库");
        }

        List<AnswerSlot> slots = expandSlots(pack.questionIds(), pool, plan.followUpDepth());
        List<String> skipped = new ArrayList<>(pack.skippedReasons());
        skipped.addAll(extraNotes);
        String planJson = writeSnapshot(new PackSnapshot(plan.totalCount(), plan.difficulties(),
            plan.followUpDepth(), pack.questionIds(), skipped));
        InterviewSessionEntity session = stateService.create(userId, directionId, planJson,
            pack.questionIds().size(), slots);
        if (decision != null) {
            persistTracesQuietly(session.getId(), userId, directionId, decision);
        }
        SessionView view = assemble(session);
        snapshot().save(view.id(), toJson(view));
        return view;
    }

    /** advisor 可用则出决策，不可用或异常返回 null（由调用方走默认策略）——决策失败不阻断开面。 */
    private PlanDecision tryAdvise(UUID userId, UUID directionId, InterviewPlan baseline) {
        PlannerAdvisorService advisor = advisors.getIfAvailable();
        if (advisor == null) {
            return null;
        }
        try {
            // 参考日走全仓日界（AppZones）：跟 JVM 默认区会随部署环境变（容器普遍 UTC），
            // 同一用户同一时刻算出不同决策 → “可复现”不成立
            return advisor.advise(userId, directionId, baseline.difficulties(),
                LocalDate.now(AppZones.DAILY));
        } catch (RuntimeException e) {
            log.warn("planner 决策异常，降级为请求原难度：{}", e.getMessage(), e);
            return null;
        }
    }

    /** 按实际进卷的复习题校正留痕；无决策或 advisor 缺席时原样返回。 */
    private PlanDecision reconcileReview(PlanDecision decision, List<UUID> packedQuestionIds) {
        if (decision == null) {
            return null;
        }
        PlannerAdvisorService advisor = advisors.getIfAvailable();
        return advisor == null ? decision : advisor.reconcileReview(decision, packedQuestionIds);
    }

    /** 留痕落库尽力而为：失败只记日志，不影响已建会话（面板降级显示“无决策记录”）。 */
    private void persistTracesQuietly(UUID sessionId, UUID userId, UUID directionId,
                                      PlanDecision decision) {
        try {
            advisors.getIfAvailable().persistTraces(sessionId, userId, directionId, decision);
        } catch (RuntimeException e) {
            log.warn("决策留痕落库失败（会话已建，面板将显示无决策记录）：{}", e.getMessage(), e);
        }
    }

    /** 会话视图（断线重进入口）：归属校 DB，slots 装配走快照尽力。 */
    public SessionView get(UUID userId, UUID sessionId) {
        InterviewSessionEntity session = ownedSession(userId, sessionId);
        if (InterviewSessionEntity.STATUS_RESUMABLE.equals(session.getStatus())) {
            var cached = snapshot().get(sessionId.toString());
            if (cached.isPresent()) {
                SessionView view = fromJson(cached.get(), SessionView.class);
                if (view != null) {
                    return view;
                }
            }
        }
        SessionView view = assemble(session);
        snapshot().save(sessionId.toString(), toJson(view));
        return view;
    }

    /** 在途会话列表（面试中心恢复入口）；directionId 传 null 列全部。 */
    public List<SessionSummary> listResumable(UUID userId, UUID directionId) {
        List<InterviewSessionEntity> sessions = directionId == null
            ? sessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                userId, InterviewSessionEntity.STATUS_RESUMABLE)
            : sessionRepository.findByUserIdAndDirectionIdAndStatusOrderByCreatedAtDesc(
                userId, directionId, InterviewSessionEntity.STATUS_RESUMABLE);
        return sessions.stream().map(s -> new SessionSummary(s.getId().toString(),
            s.getDirectionId().toString(), s.getStatus(), s.getCurrentIndex(), s.getTotalCount(),
            s.getStartedAt().toString())).toList();
    }

    /**
     * 单题作答：终态 2702 先行（预读给可读文案），提交失败 2703；主题答完推进恢复位，
     * 事务提交后 evict 快照（尽力）。
     *
     * <p>事务必须盖住两段写：{@code advanceIndexIfResumable} 是 @Modifying，
     * 无活动事务时 Hibernate 拒接（TransactionRequiredException，批 2 CI 实炸）；
     * 内部 StateService.submitAnswer 的 REQUIRED 加入本事务，不会各自提交。
     * evict 经 afterCommit 同步器挂到提交后执行（KnowledgeDocLifecycleService 的 S3
     * 删除同款先例）：提交前 evict 会被并发 {@code get()} 用旧 DB 状态回填缓存，
     * 陈旧视图存活到下次写 evict 或 24h TTL；也守住 §0.3"外部 I/O 不进事务"。
     */
    @Transactional
    public boolean answer(UUID userId, UUID sessionId, UUID questionId, int followUpIndex,
                          String text) {
        InterviewSessionEntity session = ownedSession(userId, sessionId);
        if (!InterviewSessionEntity.STATUS_RESUMABLE.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_COMPLETED);
        }
        boolean applied = stateService.submitAnswer(sessionId, userId, questionId,
            followUpIndex, text);
        if (!applied) {
            throw new BusinessException(ErrorCode.SESSION_SLOT_MISMATCH,
                "该题已作答或不在本会话中，请刷新后重试");
        }
        if (followUpIndex == 0) {
            advanceAfterMainAnswer(session, questionId);
        }
        evictAfterCommit(sessionId.toString());
        return true;
    }

    /**
     * 快照失效挂到事务提交后；无活动事务同步（单测等非事务上下文）时退化为立即失效。
     * 不用双删：快照本就是尽力缓存（24h TTL 兜底），提交后单删已把竞态窗口压到
     * "提交后、evict 前的旧读"，为此维护双删时序不值。
     */
    private void evictAfterCommit(String sessionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshot().evict(sessionId);
                }
            });
        } else {
            snapshot().evict(sessionId);
        }
    }

    /** 交卷（幂等语义在 StateService/M8）：赢者 evict 快照；败者按 2702 出口。 */
    public FinalizeView finalizeSession(UUID userId, UUID sessionId) {
        var result = stateService.finalizeSession(sessionId, userId);
        snapshot().evict(sessionId.toString());
        return new FinalizeView(result.sessionId().toString(), result.answeredCount(),
            InterviewSessionEntity.STATUS_COMPLETED, result.evaluatorVersion());
    }

    /** 手动放弃（M6）：不存在 2701；已终态 2702。 */
    public boolean abandon(UUID userId, UUID sessionId) {
        InterviewSessionEntity session = ownedSession(userId, sessionId);
        if (!InterviewSessionEntity.STATUS_RESUMABLE.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_COMPLETED,
                InterviewSessionEntity.STATUS_ABANDONED.equals(session.getStatus())
                    ? "该面试已放弃" : "该面试已交卷，无法放弃");
        }
        boolean applied = stateService.abandon(sessionId, userId);
        snapshot().evict(sessionId.toString());
        return applied;
    }

    // ---------- 内部装配 ----------

    private InterviewSessionEntity ownedSession(UUID userId, UUID sessionId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    /** 主问题作答后推进恢复位：目标 = 快照题序中该题的位次 + 1（repo 只前进不回退）。 */
    private void advanceAfterMainAnswer(InterviewSessionEntity session, UUID questionId) {
        PackSnapshot snapshot = readSnapshot(session.getPlanJson());
        if (snapshot == null) {
            return;   // 快照畸形不传染作答（读路径每次从 DB 兜底重建）
        }
        int position = snapshot.questionIds().indexOf(questionId);
        if (position >= 0) {
            sessionRepository.advanceIndexIfResumable(session.getId(),
                (short) (position + 1), Instant.now());
        }
    }

    /** 展平占位槽：主问题 1 + 追问 min(题内条数, plan depth)（interview-session-adr 决策 1）。 */
    private static List<AnswerSlot> expandSlots(List<UUID> questionIds,
                                                List<QuestionCandidate> pool, int depth) {
        Map<UUID, Integer> followUpCounts = new LinkedHashMap<>();
        pool.forEach(c -> followUpCounts.put(c.id(), c.followUpCount()));
        List<AnswerSlot> slots = new ArrayList<>();
        for (UUID id : questionIds) {
            slots.add(new AnswerSlot(id, 0));
            int layers = Math.min(followUpCounts.getOrDefault(id, 0), depth);
            for (int layer = 1; layer <= layers; layer++) {
                slots.add(new AnswerSlot(id, layer));
            }
        }
        return slots;
    }

    /** 视图装配：题序与缺口说明均取自 plan 快照（单列自含，读路径不依赖调用方传参）。 */
    private SessionView assemble(InterviewSessionEntity session) {
        PackSnapshot snapshot = readSnapshot(session.getPlanJson());
        List<UUID> order = snapshot == null ? List.of() : snapshot.questionIds();
        Map<String, SlotAnswer> answers = new LinkedHashMap<>();
        answerRepository.findBySessionIdOrderByQuestionIdAscFollowUpIndexAsc(session.getId())
            .forEach(a -> answers.put(a.getQuestionId() + ":" + a.getFollowUpIndex(),
                new SlotAnswer(a.getAnswerStatus().equals(InterviewAnswerEntity.STATUS_SUBMITTED),
                    a.getAnswerText())));
        Map<UUID, QuestionStemDetail> stems = new LinkedHashMap<>();
        questionQuery.stemsByIds(order).forEach(s -> stems.put(s.id(), s));

        List<SlotView> slots = new ArrayList<>();
        int answeredCount = 0;
        for (int position = 0; position < order.size(); position++) {
            UUID questionId = order.get(position);
            QuestionStemDetail detail = stems.get(questionId);
            String mainText = detail == null ? "（题目已删除）" : detail.question();
            slots.add(slot(position, questionId, 0, mainText, answers));
            if (detail != null) {
                // 视图展开与占位同口径（expandSlots 的 min(题内条数, depth)），否则会展示无槽可答的追问
                int layers = Math.min(detail.followUpQuestions().size(),
                    snapshot == null ? 0 : snapshot.followUpDepth());
                for (int layer = 1; layer <= layers; layer++) {
                    slots.add(slot(position, questionId, layer,
                        detail.followUpQuestions().get(layer - 1), answers));
                }
            }
        }
        answeredCount = (int) answers.values().stream().filter(SlotAnswer::answered).count();
        return new SessionView(session.getId().toString(), session.getDirectionId().toString(),
            session.getStatus(), session.getCurrentIndex(), session.getTotalCount(),
            answeredCount, slots, snapshot == null ? List.of() : snapshot.skippedReasons(),
            session.getStartedAt().toString());
    }

    private record SlotAnswer(boolean answered, String text) {
    }

    private static SlotView slot(int order, UUID questionId, int layer, String text,
                                 Map<String, SlotAnswer> answers) {
        SlotAnswer answer = answers.get(questionId + ":" + layer);
        return new SlotView(order, questionId.toString(), layer, text,
            answer != null && answer.answered(), answer == null ? null : answer.text());
    }

    private SessionSnapshotPort snapshot() {
        SessionSnapshotPort port = snapshots.getIfAvailable();
        if (port == null) {
            return NULL_SNAPSHOT;
        }
        return port;
    }

    /** Redis 整条链路缺席时的空实现（门控禁令：常驻装配必须成立）。 */
    private static final SessionSnapshotPort NULL_SNAPSHOT = new SessionSnapshotPort() {
        @Override
        public void save(String sessionId, String viewJson) {
        }

        @Override
        public Optional<String> get(String sessionId) {
            return Optional.empty();
        }

        @Override
        public void evict(String sessionId) {
        }
    };

    private String writeSnapshot(PackSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组卷快照序列化失败", e);
        }
    }

    private PackSnapshot readSnapshot(String planJson) {
        try {
            return objectMapper.readValue(planJson, PackSnapshot.class);
        } catch (JsonProcessingException e) {
            log.warn("plan 快照解析失败（视图按空题序降级）：{}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
