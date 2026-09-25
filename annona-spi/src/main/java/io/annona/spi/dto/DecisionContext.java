package io.annona.spi.dto;

import java.time.LocalDate;

/**
 * planner 执行一条规则时的输入上下文。
 *
 * @param userId 用户主键
 * @param asOf   决策参考日期（默认今天；测试里可注入历史日期以保证可复现）
 * @param signal 该用户在窗口内的学习信号快照；不可为 {@code null}
 */
public record DecisionContext(String userId, LocalDate asOf, SignalSnapshot signal) {
}
