package io.annona.shared.voice;

import io.annona.shared.interview.EvalAnswer;
import java.util.List;
import java.util.UUID;

/**
 * 语音作答只读端口（实现见 voice 模块的 {@code VoiceEvalQueryServiceImpl}）。
 * evaluation 经本端口拿语音会话的作答轮做评估——与
 * {@code InterviewEvalQueryService} 同一契约形状，返回同一 {@link EvalAnswer} 视图：
 * 评分口径同走 {@code QuestionQueryService.gradingByIds}，这是 P3-05
 * "语音与文字面试同题库得分可比"的结构保证（voice-adr 修订 1）。
 *
 * <p>只读、不得依赖返回集合的可变性；只返回关联了题目的 ANSWER 轮
 * （过渡语/结束语不进评分）。
 */
public interface VoiceEvalQueryService {

    /**
     * 该语音会话的作答轮（按 seq 保序）。
     *
     * @param userId 归属校验：会话不属该用户时返回空（防跨用户读，不抛异常）
     */
    List<EvalAnswer> submittedAnswers(UUID sessionId, UUID userId);
}
