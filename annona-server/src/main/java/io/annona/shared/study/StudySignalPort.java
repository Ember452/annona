package io.annona.shared.study;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 学习时长只读端口（实现见 study 模块的 {@code StudySignalPortImpl}）。shared/signal 门面
 * 经本端口拿某方向窗口内的学习聚合，做"方向相交"判定与质量分级过滤——只读，写需求出现
 * 即回 shared ADR 讨论（AGENTS §4）。
 */
public interface StudySignalPort {

    /**
     * 该用户在 {@code [from, to]} 闭区间内、指定方向的学习时长聚合（按质量分级拆分）。
     * 无记录时返回两个 {@link java.time.Duration#ZERO}（方向不相交的正常形态，不抛异常）。
     */
    StudySignal studySignal(UUID userId, UUID directionId, LocalDate from, LocalDate to);
}
