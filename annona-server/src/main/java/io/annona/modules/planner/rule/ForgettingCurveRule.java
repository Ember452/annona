package io.annona.modules.planner.rule;

import io.annona.modules.planner.mastery.MasteryEvent;
import io.annona.modules.planner.mastery.MasteryModel;
import io.annona.modules.planner.mastery.MasteryView;
import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.DecisionTrace;
import io.annona.spi.dto.InterviewPlan;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.planner.DecisionRule;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * FORGETTING_CURVE：掌握度衰减到 {@code forgettingFloor} 以下时，把本次难度整体下调一档
 * （让遗忘的内容以更易回锅的题重新出现），并留痕。掺入哪些复习题由 advisor 侧的复习选择器
 * 按最低分历史题决定（P1c-05），本规则只负责"要不要因遗忘而调整"。
 *
 * <p>不适用（返回 empty）的情形：样本不足、基线期——这些由 GuardEngine 统一出 NO_ADJUST/
 * BASELINE_ONLY 留痕，规则不重复（同一原因不双写）。
 */
public class ForgettingCurveRule implements DecisionRule {

    /** 掌握度事件暂以 depth=0 近似：SessionOutcome 未携带追问深度（追问放大的精度留给评估侧，
     *  触发判定对此不敏感；确需精确再扩 SPI，见 planner-decision-kernel-adr「何时重新评估」）。 */
    private static final int APPROX_FOLLOW_UP_DEPTH = 0;

    private final RuleConfig config;

    public ForgettingCurveRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public String key() {
        return "FORGETTING_CURVE";
    }

    @Override
    public Optional<MutableOutcome> apply(DecisionContext context, InterviewPlan draft) {
        SignalFacts facts = SignalFacts.from(context.signal(), config.baselineSessions());
        if (facts.sampleSize() < config.minSample() || facts.baselineOnly()) {
            return Optional.empty();
        }
        MasteryView view = mastery(context);
        if (view.mastery() >= config.forgettingFloor()) {
            return Optional.empty();
        }
        InterviewPlan adjusted = PlanDrafts.shift(draft, -1);
        return Optional.of(new MutableOutcome(adjusted, DecisionTrace.accepted(key(),
            "LOWER_DIFFICULTY_REVIEW",
            String.format("掌握度 %.2f 低于遗忘底线 %.2f，上次练习约 %d 天前，本次下调难度并掺复习题",
                view.mastery(), config.forgettingFloor(), daysSince(view, context)))));
    }

    private MasteryView mastery(DecisionContext context) {
        List<MasteryEvent> events = context.signal().recentSessions().stream()
            .filter(s -> s.compositeScore() != null)
            .sorted(Comparator.comparing(SessionOutcome::finishedAt))
            .map(s -> new MasteryEvent(s.compositeScore() / 100.0, APPROX_FOLLOW_UP_DEPTH,
                s.finishedAt()))
            .toList();
        return MasteryModel.evaluate(events, null, config.masteryParams(),
            context.asOf().atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
    }

    private long daysSince(MasteryView view, DecisionContext context) {
        if (view.lastPracticedAt() == null) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(view.lastPracticedAt(),
            context.asOf().atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
    }
}
