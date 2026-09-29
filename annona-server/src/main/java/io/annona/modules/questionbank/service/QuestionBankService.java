package io.annona.modules.questionbank.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.questionbank.dto.CapacityResponse;
import io.annona.modules.questionbank.dto.QuestionResponse;
import io.annona.modules.questionbank.dto.UpdateQuestionRequest;
import io.annona.modules.questionbank.entity.QbQuestionEntity;
import io.annona.modules.questionbank.mapper.QbQuestionMapper;
import io.annona.modules.questionbank.repository.QbQuestionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 题库维护与容量校验（P1b-03）。
 *
 * <p>容量算法（借 🅖，硬约束口径）：可用追问数 = 题目 follow_ups 中题干非空的条数；
 * 追问档位 N 可选 ⇔ ACTIVE 且难度匹配且 usableFollowUps ≥ N 的题数 ≥ 主问题数。
 * 按 0..5 逐档返回，前端禁用不可选档并计算"最多可严格保证 M 个追问"；创建会话时
 * （P1b-05）将用同一函数二次校验（TOCTOU 闭环）。纯函数 {@link #evaluateCapacity} 独立可测。
 */
@Service
public class QuestionBankService {

    /** 追问档位上限（生成端 followUpCount 0–5 的同一口径）。 */
    static final int MAX_FOLLOW_UP_TIER = 5;

    private final QbQuestionRepository questionRepository;
    private final QbQuestionMapper mapper;

    public QuestionBankService(QbQuestionRepository questionRepository, QbQuestionMapper mapper) {
        this.questionRepository = questionRepository;
        this.mapper = mapper;
    }

    /** 纯函数：各追问档位的可用题数与可选判定（输入 = 各题的可用追问数）。 */
    static List<CapacityResponse.FollowUpOption> evaluateCapacity(List<Integer> usableCounts,
                                                                  int mainQuestionCount) {
        List<CapacityResponse.FollowUpOption> options = new ArrayList<>();
        for (int tier = 0; tier <= MAX_FOLLOW_UP_TIER; tier++) {
            final int threshold = tier;
            int available = (int) usableCounts.stream().filter(c -> c >= threshold).count();
            options.add(new CapacityResponse.FollowUpOption(tier, available,
                mainQuestionCount > 0 && available >= mainQuestionCount));
        }
        return options;
    }

    /** 题库列表（可空过滤：状态 / 难度 / 题干关键词）。 */
    public List<QuestionResponse> list(UUID userId, UUID directionId, String status,
                                       Short difficulty, String keyword) {
        return questionRepository
            .search(userId, directionId, status, difficulty, blankToNull(keyword))
            .stream().map(mapper::toResponse).toList();
    }

    /** 状态变更（DRAFT ↔ ACTIVE、任意 → ARCHIVED；ARCHIVED 不复活）。 */
    @Transactional
    public QuestionResponse changeStatus(UUID userId, UUID questionId, String status) {
        // 入参形状校验先行（fail-fast），再做 owner 行查询
        if (!QbQuestionEntity.STATUS_DRAFT.equals(status)
            && !QbQuestionEntity.STATUS_ACTIVE.equals(status)
            && !QbQuestionEntity.STATUS_ARCHIVED.equals(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "status 需为 DRAFT/ACTIVE/ARCHIVED");
        }
        QbQuestionEntity entity = owned(userId, questionId);
        if (QbQuestionEntity.STATUS_ARCHIVED.equals(entity.getStatus())
            && !QbQuestionEntity.STATUS_ARCHIVED.equals(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已归档题目不支持恢复，请重新出题");
        }
        entity.setStatus(status);
        entity.setUpdatedAt(Instant.now());
        return mapper.toResponse(questionRepository.save(entity));
    }

    /** 题目编辑（null 字段不更新）。 */
    @Transactional
    public QuestionResponse update(UUID userId, UUID questionId, UpdateQuestionRequest request) {
        QbQuestionEntity entity = owned(userId, questionId);
        if (request.question() != null && !request.question().isBlank()) {
            entity.setQuestion(request.question().strip());
        }
        if (request.topicSummary() != null) {
            entity.setTopicSummary(request.topicSummary());
        }
        if (request.referenceAnswer() != null) {
            entity.setReferenceAnswer(request.referenceAnswer());
        }
        if (request.keyPoints() != null) {
            entity.setKeyPoints(request.keyPoints());
        }
        if (request.scoringRubric() != null) {
            entity.setScoringRubric(request.scoringRubric());
        }
        if (request.followUps() != null) {
            entity.setFollowUps(request.followUps().stream()
                .filter(f -> f.question() != null && !f.question().isBlank()).toList());
        }
        entity.setUpdatedAt(Instant.now());
        return mapper.toResponse(questionRepository.save(entity));
    }

    /** 物理删除（批 1 无作答记录，安全；P1b-05 起有作答史的题目走 ARCHIVED）。 */
    @Transactional
    public void delete(UUID userId, UUID questionId) {
        QbQuestionEntity entity = owned(userId, questionId);
        questionRepository.delete(entity);
    }

    /**
     * 容量校验：ACTIVE + 难度过滤 → 每题可用追问数 → 0..5 逐档判定。
     * 查询走 partial index idx_question_direction_difficulty_active。
     */
    public CapacityResponse capacity(UUID userId, UUID directionId, short difficulty,
                                     int mainQuestionCount) {
        List<Integer> usableCounts = questionRepository
            .search(userId, directionId, QbQuestionEntity.STATUS_ACTIVE, difficulty, null)
            .stream()
            .map(q -> (int) q.getFollowUps().stream()
                .filter(f -> f.question() != null && !f.question().isBlank()).count())
            .toList();
        return new CapacityResponse(evaluateCapacity(usableCounts, mainQuestionCount));
    }

    /** 行级 owner 校验：查不到即 2603，不区分不存在/他人题目。 */
    private QbQuestionEntity owned(UUID userId, UUID questionId) {
        QbQuestionEntity entity = questionRepository.findById(questionId).orElse(null);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.QB_QUESTION_NOT_FOUND);
        }
        return entity;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
