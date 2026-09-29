package io.annona.modules.questionbank.service;

import io.annona.modules.questionbank.entity.QbQuestionEntity;
import io.annona.modules.questionbank.repository.QbQuestionRepository;
import io.annona.shared.question.QuestionCandidate;
import io.annona.shared.question.QuestionQueryService;
import io.annona.shared.question.QuestionStemDetail;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link QuestionQueryService} 的 questionbank 实现（shared 读模型端口解环，
 * shared/question/package-info 记录了为什么端口不放本模块）。
 */
@Service
public class QuestionQueryServiceImpl implements QuestionQueryService {

    private final QbQuestionRepository questionRepository;

    public QuestionQueryServiceImpl(QbQuestionRepository questionRepository) {
        this.questionRepository = questionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionCandidate> activePool(UUID userId, UUID directionId) {
        return questionRepository
            .search(userId, directionId, QbQuestionEntity.STATUS_ACTIVE, null, null)
            .stream()
            .map(q -> new QuestionCandidate(q.getId(), q.getQuestion(), q.getDifficulty(),
                q.getFollowUps().size()))
            .toList();
    }

    /** 手写映射只取题干与追问文本——referenceAnswer/keyPoints/rubric 不经本视图出去。 */
    @Override
    @Transactional(readOnly = true)
    public List<QuestionStemDetail> stemsByIds(Collection<UUID> ids) {
        return questionRepository.findAllById(ids).stream()
            .map(q -> new QuestionStemDetail(q.getId(), q.getQuestion(),
                q.getFollowUps().stream().map(f -> f.question()).toList()))
            .toList();
    }
}
