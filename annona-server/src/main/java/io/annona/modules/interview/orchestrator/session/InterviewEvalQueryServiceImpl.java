package io.annona.modules.interview.orchestrator.session;

import io.annona.shared.interview.EvalAnswer;
import io.annona.shared.interview.InterviewEvalQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InterviewEvalQueryService} 的 interview 实现（shared 读端口解环，AGENTS §4：
 * evaluation 只读消费 interview 作答，不直接 import 本模块仓储）。归属校验借会话行
 * （answer 表不冗余 user_id，V9 形状）。
 */
@Service
public class InterviewEvalQueryServiceImpl implements InterviewEvalQueryService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;

    public InterviewEvalQueryServiceImpl(InterviewSessionRepository sessionRepository,
                                         InterviewAnswerRepository answerRepository) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalAnswer> submittedAnswers(UUID sessionId, UUID userId) {
        boolean owned = sessionRepository.findByIdAndUserId(sessionId, userId).isPresent();
        if (!owned) {
            return List.of();
        }
        return answerRepository.findBySessionIdOrderByQuestionIdAscFollowUpIndexAsc(sessionId)
            .stream()
            .filter(a -> InterviewAnswerEntity.STATUS_SUBMITTED.equals(a.getAnswerStatus()))
            .map(a -> new EvalAnswer(a.getQuestionId(), a.getFollowUpIndex(), a.getAnswerText()))
            .toList();
    }
}
