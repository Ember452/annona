package io.annona.modules.planner.guard;

import io.annona.modules.planner.rule.RuleConfig;
import io.annona.modules.planner.rule.SignalFacts;
import io.annona.spi.dto.DecisionTrace;
import java.util.ArrayList;
import java.util.List;

/**
 * 保护规则前置判定（设计文档 §6.4，P1c-04）。与"调整型规则"分开：调整型规则命中才留痕，
 * 而保护<b>即使拦下调整也必须显式留痕</b>——面板要能说清"这次为什么没动难度"。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>换模型/换 prompt 后基线期（{@code baselineOnly}）→ 拦下难度调整，VERSION_BASELINE 留痕；</li>
 *   <li>样本量 &lt; minSample → 拦下调整，SAMPLE_GUARD 留痕（与 SampleGuardRule 的标记留痕互斥，
 *       由本引擎统一出，避免同一原因双写）；</li>
 *   <li>学习记录全 SELF_REPORTED → 不拦面试侧调整，但记 SELF_REPORTED 逐出学习信号的说明；</li>
 *   <li>方向无学习记录 → 记"决策基于面试表现"（正常形态，非降级）。</li>
 * </ul>
 */
public final class GuardEngine {

    private GuardEngine() {
    }

    /**
     * 纯判定，无副作用。
     *
     * @param facts  从快照派生的决策事实
     * @param config 阈值（minSample 等）
     */
    public static GuardVerdict evaluate(SignalFacts facts, RuleConfig config) {
        List<DecisionTrace> notes = new ArrayList<>();
        boolean allowAdjust = true;

        if (facts.baselineOnly()) {
            allowAdjust = false;
            notes.add(DecisionTrace.accepted("VERSION_BASELINE", "BASELINE_ONLY",
                "检测到评估模型或提示版本变更，前 " + config.baselineSessions()
                    + " 场只采集基线，本次不调整难度"));
        }
        if (facts.sampleSize() < config.minSample()) {
            allowAdjust = false;
            notes.add(DecisionTrace.accepted("SAMPLE_GUARD", "NO_ADJUST",
                "该方向样本量 " + facts.sampleSize() + " < " + config.minSample()
                    + "，数据不足，本次不调整难度"));
        }
        if (facts.onlySelfReported()) {
            notes.add(DecisionTrace.accepted("SELF_REPORTED", "EXCLUDE_LEARNING_SIGNAL",
                "该方向学习记录全部来自手动自报，学习信号不参与决策（仅面试侧数据生效）"));
        }
        if (!facts.hasStudyRecord()) {
            notes.add(DecisionTrace.accepted("NO_STUDY_RECORD", "INTERVIEW_ONLY",
                "该方向无自习室记录——决策基于面试表现（正常形态，非降级）"));
        }
        return new GuardVerdict(allowAdjust, List.copyOf(notes));
    }
}
