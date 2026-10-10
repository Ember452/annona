package io.annona.modules.voice.service;

import io.annona.modules.voice.entity.VoiceSessionMessageEntity;
import io.annona.modules.voice.repository.VoiceSessionMessageRepository;
import io.annona.modules.voice.repository.VoiceSessionRepository;
import io.annona.shared.interview.EvalAnswer;
import io.annona.shared.voice.VoiceEvalQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 语音作答只读装配（P3-05，voice-adr 修订 1）：evaluation 经 shared 接口消费，
 * 模块间零直连。只返回<b>关联了题目的 ANSWER 轮</b>——过渡语/结束语不进评分；
 * followUpIndex 恒 0（语音轮无追问槽位）。归属校验：会话不属该用户返回空。
 */
@Service
public class VoiceEvalQueryServiceImpl implements VoiceEvalQueryService {

    private final VoiceSessionRepository sessions;
    private final VoiceSessionMessageRepository messages;

    public VoiceEvalQueryServiceImpl(VoiceSessionRepository sessions,
                                     VoiceSessionMessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    @Override
    public List<EvalAnswer> submittedAnswers(UUID sessionId, UUID userId) {
        if (sessions.findByIdAndUserId(sessionId, userId).isEmpty()) {
            return List.of();
        }
        return messages
            .findBySessionIdAndRoleAndQuestionIdIsNotNullOrderBySeqAsc(sessionId,
                VoiceSessionMessageEntity.ROLE_ANSWER)
            .stream()
            .map(m -> new EvalAnswer(m.getQuestionId(), 0, m.getContent()))
            .toList();
    }
}
