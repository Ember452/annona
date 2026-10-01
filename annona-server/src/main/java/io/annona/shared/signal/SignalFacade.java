package io.annona.shared.signal;

import io.annona.shared.evaluation.EvaluationSignalPort;
import io.annona.shared.study.StudySignal;
import io.annona.shared.study.StudySignalPort;
import io.annona.spi.dto.DirectionSignal;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.dto.SignalSnapshot;
import io.annona.spi.signal.LearningSignalReader;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link LearningSignalReader} 的应用内实现（planner 的唯一信号入口）：组合 study 与
 * evaluation 两个只读端口，产出方向感知的信号快照。
 *
 * <p>门控纪律（AGENTS §4）：两端经 {@link ObjectProvider} 注入而非硬依赖——study/evaluation
 * 模块整体缺席时（全门关启动 IT）本 bean 仍能装配，方向快照降级为"面试侧与学习侧皆空"的
 * 正常形态，planner 据此走 SAMPLE_GUARD 而非崩溃。
 *
 * <p>取舍：门面现算不缓存——决策在开面时一次性触发（每天几次），加缓存要多一个失效点、
 * 且破坏"同用户同日同方向快照可复现"的 golden 语义，不值。
 */
@Component
public class SignalFacade implements LearningSignalReader {

    private final ObjectProvider<StudySignalPort> studyPorts;
    private final ObjectProvider<EvaluationSignalPort> evaluationPorts;

    public SignalFacade(ObjectProvider<StudySignalPort> studyPorts,
                        ObjectProvider<EvaluationSignalPort> evaluationPorts) {
        this.studyPorts = studyPorts;
        this.evaluationPorts = evaluationPorts;
    }

    /** 用户级读取保留接口兼容：本门面不跨方向展开，返回空方向明细（见接口 Javadoc）。 */
    @Override
    public SignalSnapshot read(String userId, LocalDate from, LocalDate to) {
        return new SignalSnapshot(userId, from, to, Duration.ZERO, null, 0, List.of(), List.of());
    }

    @Override
    public SignalSnapshot readDirectional(String userId, UUID directionId,
                                          LocalDate from, LocalDate to) {
        UUID uid = UUID.fromString(userId);
        StudySignal study = Optional.ofNullable(studyPorts.getIfAvailable())
            .map(p -> p.studySignal(uid, directionId, from, to))
            .orElseGet(() -> new StudySignal(Duration.ZERO, Duration.ZERO));
        List<SessionOutcome> outcomes = Optional.ofNullable(evaluationPorts.getIfAvailable())
            .map(p -> p.recentOutcomes(uid, directionId, from, to))
            .orElse(List.of());

        int sessions = outcomes.size();
        Double avgScore = averageScore(outcomes);
        DirectionSignal directional = new DirectionSignal(
            directionId.toString(),
            sessions,
            avgScore,
            lastPracticedAt(outcomes),
            study.verifiedMinutes(),
            study.selfReportedMinutes());

        Duration totalStudy = study.verifiedMinutes().plus(study.selfReportedMinutes());
        return new SignalSnapshot(userId, from, to, totalStudy, null, sessions,
            List.of(directional), outcomes);
    }

    /** 非降级分（compositeScore != null）的均值，四舍五入到整数分再转 Double；全 null 或空 → null。 */
    private static Double averageScore(List<SessionOutcome> outcomes) {
        List<Integer> scores = outcomes.stream().map(SessionOutcome::compositeScore)
            .filter(java.util.Objects::nonNull).toList();
        if (scores.isEmpty()) {
            return null;
        }
        double mean = scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        return Math.round(mean * 100.0) / 100.0;
    }

    /** 最近一次交卷时刻：取 finishedAt 最大值（不依赖端口返回顺序，消除顺序耦合）。 */
    private static java.time.Instant lastPracticedAt(List<SessionOutcome> outcomes) {
        return outcomes.stream().map(SessionOutcome::finishedAt)
            .filter(java.util.Objects::nonNull)
            .max(java.util.Comparator.naturalOrder()).orElse(null);
    }
}
