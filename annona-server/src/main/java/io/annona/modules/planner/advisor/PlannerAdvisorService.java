package io.annona.modules.planner.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.modules.planner.rule.PlanDrafts;
import io.annona.modules.planner.rule.RuleChain;
import io.annona.modules.planner.rule.RuleConfig;
import io.annona.modules.planner.reputation.RuleReputationService;
import io.annona.modules.planner.trace.DecisionTraceWriter;
import io.annona.shared.evaluation.EvaluationSignalPort;
import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DecisionTrace;
import io.annona.spi.dto.SignalSnapshot;
import io.annona.spi.planner.DecisionRule;
import io.annona.spi.signal.LearningSignalReader;
import java.time.LocalDate;
import java.util.ArrayList;
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

/**
 * planner 对外唯一入口（P1c-05）：interview/orchestrator 在组卷前同步调用本服务拿决策。
 * 依赖方向 {@code interview/orchestrator → planner/advisor} 是 AGENTS §4 白名单例外②。
 *
 * <p><b>纯读编排、无事务</b>：只经 {@link LearningSignalReader} 读信号、跑 {@link RuleChain} 出难度
 * 序列与留痕；trace 落库由 {@code DecisionTraceWriter} 在会话创建后独立事务完成（本方法不写库、
 * 不占事务）。异常语义：信号端口缺席时门面已降级为空快照，本方法不抛；调用方（Facade）对
 * advisor 的任何异常兜底降级为请求原难度——决策失败绝不阻断开面。
 *
 * <p>取舍：不缓存决策——每次开面现算（每天几次、单方向事件 ≤10 场），缓存要多失效点且破坏
 * "决策可复现"（planner-decision-kernel-adr 否决表）。
 */
@Service
public class PlannerAdvisorService {

    private static final Logger log = LoggerFactory.getLogger(PlannerAdvisorService.class);

    private final LearningSignalReader signalReader;
    private final RuleConfig config;
    private final List<DecisionRule> rules;
    private final ObjectProvider<EvaluationSignalPort> evaluationPorts;
    private final DecisionTraceWriter traceWriter;
    private final RuleReputationService reputationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlannerAdvisorService(LearningSignalReader signalReader, RuleConfig config,
                                 List<DecisionRule> rules,
                                 ObjectProvider<EvaluationSignalPort> evaluationPorts,
                                 DecisionTraceWriter traceWriter,
                                 RuleReputationService reputationService) {
        this.signalReader = signalReader;
        this.config = config;
        this.rules = rules;
        this.evaluationPorts = evaluationPorts;
        this.traceWriter = traceWriter;
        this.reputationService = reputationService;
    }

    /**
     * 会话创建后落本次决策的留痕（interview 侧携 sessionId 回调）。单独事务，写失败不
     * 影响已建会话（面板降级显示“无决策记录”）。Facade 只经本方法写，不直接依赖 trace 包。
     */
    public void persistTraces(java.util.UUID sessionId, java.util.UUID userId,
                              java.util.UUID directionId, PlanDecision decision) {
        traceWriter.persist(sessionId, userId, directionId, decision.traces(),
            decision.inputSnapshotJson());
    }

    /**
     * 出一场面试的组卷决策。
     *
     * @param userId             决策主体
     * @param directionId        方向
     * @param baselineDifficulties 请求的基线难度序列（长度即 totalCount；无规则命中时原样返回）
     * @param asOf               决策参考日期（测试注入历史日期保证可复现）
     */
    public PlanDecision advise(UUID userId, UUID directionId,
                               List<Integer> baselineDifficulties, LocalDate asOf) {
        LocalDate from = asOf.minusDays(Math.max(1, config.windowDays()));
        SignalSnapshot snapshot =
            signalReader.readDirectional(userId.toString(), directionId, from, asOf);
        DecisionContext context = new DecisionContext(userId.toString(), asOf, snapshot);

        // 声誉过滤：被该用户驳回达阈值停用的规则不进链（P1c-07，降权改变后续决策）
        Set<String> disabled = reputationService.disabledRuleKeys(userId);
        List<DecisionRule> activeRules = rules.stream()
            .filter(r -> !disabled.contains(r.key()))
            .toList();

        RuleChain.Result chain = new RuleChain(config).run(context,
            PlanDrafts.fromDifficulties(directionId.toString(), baselineDifficulties), activeRules);
        List<Integer> difficulties = PlanDrafts.difficultiesOf(chain.plan());

        List<UUID> reviewIds = selectReviewIds(userId, directionId, baselineDifficulties.size(),
            chain.traces());
        List<DecisionTrace> traces = new ArrayList<>(chain.traces());
        if (!reviewIds.isEmpty()) {
            // 复习提醒以 trace 行呈现（P1c-07 反哺落点；真 todo 回写待 P2-06 建表）
            traces.add(DecisionTrace.accepted("REMIND_REVIEW", "SUGGEST_REVIEW",
                "建议优先复习最近得分最低的 " + reviewIds.size() + " 题（遗忘曲线命中）"));
        }
        String snapshotJson = serializeSnapshot(snapshot);
        return new PlanDecision(difficulties, reviewIds, traces, snapshotJson);
    }

    /** 仅当本次有 FORGETTING_CURVE 命中才掺复习题，数量 = reviewRatio*槽数（下限 1、上限 totalCount）。 */
    private List<UUID> selectReviewIds(UUID userId, UUID directionId, int totalCount,
                                       List<DecisionTrace> traces) {
        boolean forgettingFired = traces.stream()
            .anyMatch(t -> "FORGETTING_CURVE".equals(t.ruleKey()));
        if (!forgettingFired) {
            return List.of();
        }
        int limit = Math.min(totalCount, Math.max(1, (int) Math.round(config.reviewRatio() * totalCount)));
        return Optional.ofNullable(evaluationPorts.getIfAvailable())
            .map(p -> p.weakestQuestionIds(userId, directionId, limit))
            .orElseGet(() -> {
                log.debug("evaluation 端口缺席，本次不掺复习题（决策仍基于难度调整）");
                return List.of();
            });
    }

    /** 决策依据快照序列化为 JSON（供面板核对）；失败降级 null，不影响决策本身。 */
    private String serializeSnapshot(SignalSnapshot snapshot) {
        try {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("asOf", snapshot.to().toString());
            brief.put("sampleSize", snapshot.sampleSize());
            if (!snapshot.directionals().isEmpty()) {
                var d = snapshot.directionals().get(0);
                brief.put("avgScore", d.avgScore());
                brief.put("hasStudyRecord", d.hasStudyRecord());
                brief.put("onlySelfReported", d.studyOnlySelfReported());
            }
            return objectMapper.writeValueAsString(brief);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
