package io.annona.shared.interview;

import java.util.List;
import java.util.UUID;

/**
 * 面试作答只读端口（实现见 interview 模块的 {@code InterviewEvalQueryServiceImpl}）。
 * evaluation 经本端口拿某会话的已提交作答做评估——只读、不得依赖返回集合可变性、
 * 写需求出现即回 shared ADR 讨论（AGENTS §4）。
 */
public interface InterviewEvalQueryService {

    /**
     * 该会话的已提交作答（按题目、追问序）。
     *
     * @param userId 归属校验：会话不属该用户时返回空（防跨用户读，不抛异常——评估消费端据此判失败）
     */
    List<EvalAnswer> submittedAnswers(UUID sessionId, UUID userId);
}
