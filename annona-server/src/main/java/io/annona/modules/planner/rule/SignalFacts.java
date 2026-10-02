package io.annona.modules.planner.rule;

import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.dto.SignalSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * 从 {@link SignalSnapshot} 派生的决策事实（纯函数，无 IO）。规则与 guard 共读同一份事实，
 * 避免各自从快照重复解析出不同口径（一次决策内 SAMPLE_GUARD 与 WEAK_DIRECTION 看到的
 * sampleSize 必须是同一个数）。
 *
 * @param sampleSize       有效面试样本数（最近 ≤10 场中有非降级分的场数）——与 {@code avgScore}
 *                         同分母，<b>不按时间窗口截断</b>（planner-adr 修订 1）
 * @param avgScore         有效样本总分均值 [0,100]；无有效分为 {@code null}
 * @param hasStudyRecord   该方向是否存在学习记录（相交）
 * @param onlySelfReported 学习记录是否全来自 SELF_REPORTED（逐出决策，只进展示）
 * @param baselineOnly     是否处于"换模型/prompt 后的基线采集期"（近 {@code baselineSessions}
 *                         场内出现留痕变更 → 只采基线、不调难度，§6.4 保护 4）
 */
public record SignalFacts(
    int sampleSize,
    Double avgScore,
    boolean hasStudyRecord,
    boolean onlySelfReported,
    boolean baselineOnly) {

    /**
     * 派生事实。
     *
     * @param snapshot         方向感知快照（{@code directionals} 已按方向过滤）
     * @param baselineSessions 换模型后的基线场数（来自 PlannerProperties）
     */
    public static SignalFacts from(SignalSnapshot snapshot, int baselineSessions) {
        var directional = snapshot.directionals().isEmpty()
            ? null : snapshot.directionals().get(0);
        boolean hasStudy = directional != null && directional.hasStudyRecord();
        boolean onlySelf = directional != null && directional.studyOnlySelfReported();
        Double avg = directional == null ? null : directional.avgScore();
        int sample = snapshot.sampleSize();
        boolean baseline = detectBaseline(snapshot.recentSessions(), baselineSessions);
        return new SignalFacts(sample, avg, hasStudy, onlySelf, baseline);
    }

    /**
     * 基线检测：交卷时间倒序的最近若干场中，若在"最近 baselineSessions 场"与前一场之间
     * 出现 (chatModel / evaluatorModel / promptHash / evaluatorVersion) 任一变化，则当前
     * 处于基线采集期（最近 baselineSessions 场只采基线）。留痕为 null 的场不参与比对
     * （无法比对时保持基线连续性，宁可不判也不误断）。
     */
    private static boolean detectBaseline(List<SessionOutcome> descSessions,
                                          int baselineSessions) {
        if (descSessions == null || descSessions.size() < baselineSessions + 1) {
            return false;
        }
        // 越过基线窗口的那一场（第 baselineSessions+1 场，即窗口起点之前的锚点）
        var anchor = descSessions.get(baselineSessions);
        var within = descSessions.subList(0, baselineSessions);
        for (var s : within) {
            if (bothTraced(anchor, s) && differs(anchor, s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bothTraced(SessionOutcome a, SessionOutcome b) {
        return a.promptHash() != null && b.promptHash() != null
            && a.evaluatorModel() != null && b.evaluatorModel() != null;
    }

    private static boolean differs(SessionOutcome a, SessionOutcome b) {
        return !a.promptHash().equals(b.promptHash())
            || !a.evaluatorModel().equals(b.evaluatorModel())
            || !Objects.equals(a.chatModel(), b.chatModel());
    }
}
