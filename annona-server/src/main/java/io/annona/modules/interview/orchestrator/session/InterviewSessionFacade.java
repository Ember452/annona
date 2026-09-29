package io.annona.modules.interview.orchestrator.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.cache.SessionSnapshotPort;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.interview.orchestrator.controller.CreateSessionRequest;
import io.annona.modules.interview.orchestrator.controller.FinalizeView;
import io.annona.modules.interview.orchestrator.controller.SessionSummary;
import io.annona.modules.interview.orchestrator.controller.SessionView;
import io.annona.modules.interview.orchestrator.controller.SlotView;
import io.annona.modules.interview.orchestrator.pack.QuestionDedupService;
import io.annona.modules.interview.orchestrator.pack.QuestionPackService;
import io.annona.modules.interview.orchestrator.plan.InterviewPlan;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.shared.question.QuestionCandidate;
import io.annona.shared.question.QuestionQueryService;
import io.annona.shared.question.QuestionStemDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterviewSessionFacade(InterviewSessionStateService stateService,
                                  InterviewSessionRepository sessionRepository,
                                  InterviewAnswerRepository answerRepository,
                                  DirectionQueryService directionQuery,
                                  QuestionQueryService questionQuery,
                                  QuestionDedupService dedupService,
                                  QuestionPackService packService,
                                  ObjectProvider<SessionSnapshotPort> snapshots) {
        this.stateService = stateService;
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.directionQuery = directionQuery;
        this.questionQuery = questionQuery;
        this.dedupService = dedupService;
        this.packService = packService;
        this.snapshots = snapshots;
    }

    /**
     * 开始面试：计划校验（1001）→ 方向可见（2100）→ 组卷（容量不足 2604）→ 同方向旧会话
     * 自动 ABANDONED（返回值条数随视图 skippedReasons 提示）→ 落库并写快照。
     */
    public SessionView create(UUID userId, CreateSessionRequest request) {
        InterviewPlan plan;
        try {
            plan = new InterviewPlan(request.totalCount(), request.difficulties(),
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
        var pack = packService.pack(plan, pool,
            dedupService.findDedupHits(userId, directionId, pool));
        if (pack.questionIds().isEmpty()) {
            throw new BusinessException(ErrorCode.QB_QUESTION_CAPACITY_INSUFFICIENT,
                "近 90 天该方向题目已答过或被去重拦截，请先补充题库");
        }

        List<AnswerSlot> slots = expandSlots(pack.questionIds(), pool, plan.followUpDepth());
        String planJson = writeSnapshot(new PackSnapshot(plan.totalCount(), plan.difficulties(),
            plan.followUpDepth(), pack.questionIds(), pack.skippedReasons()));
        InterviewSessionEntity session = stateService.create(userId, directionId, planJson,
            pack.questionIds().size(), slots);
        SessionView view = assemble(session);
        snapshot().save(view.id(), toJson(view));
        return view;
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
     * DB 成功后 evict 快照（尽力）。
     */
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
        snapshot().evict(sessionId.toString());
        return true;
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
