package io.annona.shared.question;

import java.util.List;
import java.util.UUID;

/**
 * 题库只读端口（实现见 questionbank 的 {@code QuestionQueryServiceImpl}）。
 * 消费方约束：只读、不得依赖返回集合的可变性、实现在各自模块——本接口不做写路径扩展，
 * 写需求出现即回 shared ADR 讨论（AGENTS §4"写路径一律领域事件"）。
 */
public interface QuestionQueryService {

    /** 该用户该方向的全部 ACTIVE 题目（组卷池，题库侧时间倒序——同难度新题优先）。 */
    List<QuestionCandidate> activePool(UUID userId, UUID directionId);
}
