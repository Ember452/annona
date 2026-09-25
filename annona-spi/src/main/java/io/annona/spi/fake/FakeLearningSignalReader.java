package io.annona.spi.fake;

import io.annona.spi.dto.SignalSnapshot;
import io.annona.spi.signal.LearningSignalReader;
import java.time.Duration;
import java.time.LocalDate;

/**
 * {@link LearningSignalReader} 的内存实现。返回**全零**的快照，符合接口
 * Javadoc 里"无数据时返回字段全零的快照，禁止抛异常"的约定。
 *
 * <p>P1c 的 planner 拿到 sampleSize=0 时会走 guard 分支（样本不足则不改难度、
 * 退化为均匀出题），这个 fake 正好触发那条降级路径做单测。
 */
public final class FakeLearningSignalReader implements LearningSignalReader {

    @Override
    public SignalSnapshot read(String userId, LocalDate from, LocalDate to) {
        return new SignalSnapshot(
            userId,
            from,
            to,
            Duration.ZERO,
            Integer.valueOf(0),
            0);
    }
}
