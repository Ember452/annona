package io.annona.modules.resume.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.resume.dto.ResumeView;
import io.annona.modules.resume.entity.ResumeEntity;
import io.annona.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 简历只读服务（列表/详情）。归属校验：非 owner 与不存在一律 3100，不泄露存在性。 */
@Service
public class ResumeQueryService {

    private final ResumeRepository resumeRepository;

    public ResumeQueryService(ResumeRepository resumeRepository) {
        this.resumeRepository = resumeRepository;
    }

    @Transactional(readOnly = true)
    public List<ResumeView> list(String userId) {
        return resumeRepository.findByUserIdOrderByCreatedAtDesc(parseUser(userId)).stream()
            .map(ResumeQueryService::toView)
            .toList();
    }

    @Transactional(readOnly = true)
    public ResumeView detail(String userId, String id) {
        UUID user = parseUser(userId);
        UUID resumeId;
        try {
            resumeId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }
        ResumeEntity resume = resumeRepository.findByIdAndUserId(resumeId, user)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        return toView(resume);
    }

    private static ResumeView toView(ResumeEntity r) {
        return new ResumeView(r.getId().toString(), r.getName(), r.getStatus(), r.getAnalysis(),
            r.getError(), String.valueOf(r.getCreatedAt()));
    }

    private static UUID parseUser(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }
    }
}
