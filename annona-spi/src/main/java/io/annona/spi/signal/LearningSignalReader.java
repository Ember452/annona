package io.annona.spi.signal;

import io.annona.spi.dto.SignalSnapshot;
import java.time.LocalDate;

/**
 * 学习信号读取扩展点。planner 的唯一外部输入源。
 *
 * <p>annona-server 的 {@code shared/signal} 提供门面 {@code SignalFacade}，聚合 {@code study /
 * plan / interview / evaluation} 的原始数据并做质量分级过滤（{@code VERIFIED} 才计入
 * {@code totalStudy}），再暴露本接口。第三方可以覆盖实现（例如接入外部 LMS 的数据）。
 */
public interface LearningSignalReader {

    /**
     * 读取用户在 {@code [from, to]} 闭区间内的信号快照。
     * 无数据时返回字段全零的快照，禁止抛异常。
     */
    SignalSnapshot read(String userId, LocalDate from, LocalDate to);
}
