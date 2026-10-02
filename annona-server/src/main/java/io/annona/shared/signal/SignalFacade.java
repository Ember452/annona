package io.annona.shared.signal;

import io.annona.shared.evaluation.EvaluationSignalPort;
import io.annona.shared.study.StudySignal;
import io.annona.shared.study.StudySignalPort;
import io.annona.spi.dto.DirectionSignal;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.dto.SignalSnapshot;
import io.annona.spi.signal.LearningSignalReader;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

    /**
     * 掌握度事件的事件上限（= SPI 契约里的 {@code recentSessions} 上限 10 场，见
     * {@link SignalSnapshot}）。写成常量而非配置键：它是契约的一部分（快照形状
     * 不得随环境变），不是可调参数。
     */
    private static final int OUTCOME_SAMPLE_LIMIT = 10;

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
        // 学习侧按窗口回看（“近 N 天有专注”只在短窗口内有意义）；
        // 面试侧取最近固定条数、不受窗口限制（久不练必须看得到，planner-adr 修订 1）
        StudySignal study = Optional.ofNullable(studyPorts.getIfAvailable())
            .map(p -> p.studySignal(uid, directionId, from, to))
            .orElseGet(() -> new StudySignal(Duration.ZERO, Duration.ZERO));
        List<SessionOutcome> outcomes = Optional.ofNullable(evaluationPorts.getIfAvailable())
            .map(p -> p.latestOutcomes(uid, directionId, OUTCOME_SAMPLE_LIMIT))
            .orElse(List.of());

        // 有效样本 = 有非降级分**且有交卷时刻**的场次。三个条件缺一不可：无分则无均分可说，
        // 无时刻则掌握度算不出衰减天数（缺后一项时，规则链排序会直接 NPE，
        // 被调用方吞成“决策未参与”）。sampleSize、avgScore、lastPracticedAt 与掌握度事件
        // 共用这一个集合，面板里“近 N 场均分 X”的 N 才是 X 的真分母。
        List<SessionOutcome> usable = outcomes.stream()
            .filter(s -> s.compositeScore() != null && s.finishedAt() != null)
            .toList();
        int sampleSize = usable.size();
        Double avgScore = averageScore(usable);
        DirectionSignal directional = new DirectionSignal(
            directionId.toString(),
            sampleSize,
            avgScore,
            lastPracticedAt(outcomes),
            study.verifiedMinutes(),
            study.selfReportedMinutes());

        Duration totalStudy = study.verifiedMinutes().plus(study.selfReportedMinutes());
        return new SignalSnapshot(userId, from, to, totalStudy, null, sampleSize,
            List.of(directional), usable);
    }

    /** 有效样本分的均值，保留两位小数；空集 → null（无分可说，面板显“数据不足”而非 0 分）。 */
    private static Double averageScore(List<SessionOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return null;
        }
        double mean = outcomes.stream().mapToInt(SessionOutcome::compositeScore).average()
            .orElseThrow(() -> new IllegalStateException("非空事件集的均分不应缺失"));
        return Math.round(mean * 100.0) / 100.0;
    }

    /**
     * 最近一次交卷时刻：取 finishedAt 最大值（不依赖端口返回顺序，消除顺序耦合）。
     *
     * <p>故意不跟 {@code usable} 同集合：“上次练习 N 天前”问的是练过没有，降级场也是真练过；
     * 拿它当样本量或均分才是错的（那两项才要同一个分母）。
     */
    private static Instant lastPracticedAt(List<SessionOutcome> outcomes) {
        return outcomes.stream().map(SessionOutcome::finishedAt)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder()).orElse(null);
    }
}
