package io.annona.spi.signal;

import io.annona.spi.dto.DirectionSignal;
import io.annona.spi.dto.SignalSnapshot;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 学习信号读取扩展点。planner 的唯一外部输入源。
 *
 * <p>annona-server 的 {@code shared/signal} 提供门面 {@code SignalFacade}，聚合 {@code study /
 * evaluation} 的原始数据并做质量分级过滤（{@code VERIFIED}/{@code PARTIAL} 才计入有效时长，
 * {@code SELF_REPORTED} 单列），再暴露本接口。第三方可以覆盖实现（例如接入外部 LMS 的数据）。
 */
public interface LearningSignalReader {

    /**
     * 读取用户在 {@code [from, to]} 闭区间内的用户级信号快照。P1c 决策链按方向工作，
     * 只用 {@link #readDirectional}；本方法保留给外部实现方与未来的跨方向展示，
     * 内置门面不跨方向展开（诚实返回空方向明细，不编造未聚合的数据）。无数据时
     * 返回字段全零的快照，禁止抛异常。
     */
    SignalSnapshot read(String userId, LocalDate from, LocalDate to);

    /**
     * 读取单一方向的信号快照（修订 4，direction ADR 修订 3 遗留义务）：返回的快照只含
     * {@code directionId} 一个方向（{@code directionals.size()==1}，无记录时为全零的
     * {@link DirectionSignal}——方向不相交是正常形态而非降级）。决策链按本口径工作，
     * 禁止用用户级聚合做方向内比较。
     *
     * <p>接口默认实现返回空零值快照而非抛 {@code UnsupportedOperationException}：
     * 外部已发布的实现方升级 spi 时不被迫改代码，门面实现覆写本方法即为真实行为。
     */
    default SignalSnapshot readDirectional(String userId, UUID directionId,
                                           LocalDate from, LocalDate to) {
        DirectionSignal zero = new DirectionSignal(directionId.toString(), 0, null, null,
            Duration.ZERO, Duration.ZERO);
        return new SignalSnapshot(userId, from, to, Duration.ZERO, null, 0,
            List.of(zero), List.of());
    }
}
